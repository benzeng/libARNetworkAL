package com.parrot.arsdk.arnetworkal;

import android.R.integer;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothGattCharacteristic;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;

import com.parrot.arsdk.arnetworkal.ARNETWORKAL_ERROR_ENUM;
import com.parrot.arsdk.arnetworkal.ARNETWORKAL_MANAGER_RETURN_ENUM;
import com.parrot.arsdk.arsal.ARSALPrint;

public class ARNetworkALBLENetwork implements ARNetworkALBLEManagerListener
{
    private static String TAG = "ARNetworkALBLENetwork";
    
    private static String ARNETWORKAL_BLENETWORK_PARROT_SERVICE_PREFIX_UUID = "0000f";
    private static int ARNETWORKAL_BLENETWORK_MEDIA_MTU = 0;
    private static int ARNETWORKAL_BLENETWORK_HEADER_SIZE = 0;
    
    private static int ARNETWORKAL_BLENETWORK_BW_PROGRESS_EACH_SEC = 1;
    private static int ARNETWORKAL_BLENETWORK_BW_NB_ELEMS = 10;
    
    private native static void nativeJNIInit();
    private native static int nativeGetMediaMTU ();
    private native static int nativeGetHeaderSize();
    
    private native static void nativeJNIOnDisconect (int jniARNetworkALBLENetwork);
    private ARNetworkALBLEManager bleManager;
    
    private BluetoothDevice deviceBLEService;
    
    private BluetoothGattService recvService;
    private BluetoothGattService sendService;
    private ArrayList<BluetoothGattCharacteristic> array;
    
    private int[] bwElementUp;
    private int[] bwElementDown;
    private int bwIndex;
    private Semaphore bwSem;
    private Semaphore bwThreadRunning;
    private int bwCurrentUp;
    private int bwCurrentDown;
    
    private int jniARNetworkALBLENetwork;
    
    static
    {
        ARNETWORKAL_BLENETWORK_MEDIA_MTU = nativeGetMediaMTU ();
        ARNETWORKAL_BLENETWORK_HEADER_SIZE = nativeGetHeaderSize();
        nativeJNIInit();
    }
    
    public ARNetworkALBLENetwork (int jniARNetworkALBLENetwork)
    {
        this.bleManager = null;
        this.array = new ArrayList<BluetoothGattCharacteristic>();
        this.jniARNetworkALBLENetwork = jniARNetworkALBLENetwork;
        
        this.bwElementUp = new int[ARNETWORKAL_BLENETWORK_BW_NB_ELEMS];
        this.bwElementDown = new int[ARNETWORKAL_BLENETWORK_BW_NB_ELEMS];
        this.bwSem = new Semaphore (0);
        this.bwThreadRunning = new Semaphore (0);
    }
    
    public int connect (ARNetworkALBLEManager bleManager, BluetoothDevice deviceBLEService, int[] notificationIDArray)
    {
        ARNETWORKAL_ERROR_ENUM result = ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_OK;
        BluetoothGattService senderService = null;
        BluetoothGattService receiverService = null;
        
        if (deviceBLEService == null)
        {
            result = ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_ERROR_BAD_PARAMETER;
        }
        
        if (result == ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_OK)
        {
            /* connect to the device */
            result = bleManager.connect(deviceBLEService);
        }
        
        if (result == ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_OK)
        {
            result = bleManager.discoverBLENetworkServices();
        }
        
        /* look for the receiver service and the sender service */
        if (result == ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_OK)
        {
            BluetoothGatt gatt = bleManager.getGatt ();
            List<BluetoothGattService> serviesArray = gatt.getServices();
            
            for (int index = 0 ; index < serviesArray.size() && ((senderService == null) || (receiverService == null)) ; index++ )
            {
                BluetoothGattService gattService = serviesArray.get(index);
                
                /* check if it is a parrot service */
                if (gattService.getUuid().toString().startsWith(ARNETWORKAL_BLENETWORK_PARROT_SERVICE_PREFIX_UUID))
                {
                    /* if there is any characteristic */
                    if (gattService.getCharacteristics().size() > 0)
                    {
                        BluetoothGattCharacteristic gattCharacteristic = gattService.getCharacteristics().get(0);
                        
                        if ((senderService == null) && ((gattCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) == BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE))
                        {
                            senderService = gattService;
                        }
                        
                        if ((receiverService == null) && ((gattCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == BluetoothGattCharacteristic.PROPERTY_NOTIFY))
                        {
                            receiverService = gattService;
                        }
                    }
                }
                /*
                 * NO ELSE
                 * It's not a Parrot characteristic, ignore it
                 */
            }
            
            if ((senderService == null) || (receiverService == null))
            {
                result = ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_ERROR_BLE_SERVICES_DISCOVERING;
            }
        }
        
        if (result == ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_OK)
        {
            ARSALPrint.d(TAG, "senderService: " + senderService.getUuid());
            ARSALPrint.d(TAG, "receiverService: " + receiverService.getUuid());
            
            bwIndex = 0;
            bwCurrentUp = 0;
            bwCurrentDown = 0;
            for (int i = 0 ; i < ARNETWORKAL_BLENETWORK_BW_NB_ELEMS ; i++)
            {
                bwElementUp[i] = 0;
                bwElementDown[i] = 0;
            }
            bwThreadRunning.release();
            
            this.bleManager = bleManager;//TODO see
            this.deviceBLEService = deviceBLEService;
            this.recvService = receiverService;
            this.sendService = senderService;
            
            this.bleManager.setListener(this);
            
            List<BluetoothGattCharacteristic> notificationCharacteristics = null;
            if (notificationIDArray != null)
            {
                notificationCharacteristics = new ArrayList<BluetoothGattCharacteristic>();
                /* Add the characteristics to be notified */
                for (int id : notificationIDArray)
                {
                    notificationCharacteristics.add(receiverService.getCharacteristics().get(id));
                }
            }
            else
            {
                notificationCharacteristics = receiverService.getCharacteristics();
            }
            
            /* Notify the characteristics */
            ARNETWORKAL_ERROR_ENUM setNotifCharacteristicResult = ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_OK; //TODO see
            for (BluetoothGattCharacteristic gattCharacteristic : notificationCharacteristics)
            {
                /* if the characteristic can be notified */
                if ((gattCharacteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) == BluetoothGattCharacteristic.PROPERTY_NOTIFY)
                {
                    setNotifCharacteristicResult = bleManager.setCharacteristicNotification (receiverService, gattCharacteristic);
                }
                
                switch (setNotifCharacteristicResult)
                {
                    case ARNETWORKAL_OK:
                        /* notification successfully set */
                        /* do nothing */
                        break;
                        
                    case ARNETWORKAL_ERROR_BLE_CHARACTERISTIC_CONFIGURING:
                        /* This service is unknown by ARNetworkAL*/
                        /* do nothing */
                        break;
                        
                    case ARNETWORKAL_ERROR_BLE_NOT_CONNECTED:
                        /* the peripheral is disconnected */
                        result = ARNETWORKAL_ERROR_ENUM.ARNETWORKAL_ERROR_BLE_CONNECTION;
                        break;
                        
                    default:
                        ARSALPrint.e (TAG, "error " + setNotifCharacteristicResult + " unexpected :  " + setNotifCharacteristicResult);
                        break;
                }
            }
        }
        
        return result.getValue();
    }
    
    public void cancel ()
    {
        ARSALPrint.d(TAG, "cancel");
        
        disconnect ();
        
        /* reset the BLEManager for a new use */
        bleManager.reset();
    }
    
    public void disconnect ()
    {
        synchronized (this)
        {
            if(deviceBLEService != null)
            {
                ARSALPrint.d(TAG, "disconnect");
                
                bwSem.release();
                try
                {
                    bwThreadRunning.acquire ();
                }
                catch (InterruptedException e)
                {
                    e.printStackTrace();
                }
                
                bleManager.disconnect();
                
                deviceBLEService = null;
                bleManager.setListener(null);
                
                bleManager = null; //TODO see
            }
        }
    }
    
    private void unlock ()
    {
        ARSALPrint.d(TAG, "unlock");
        
        bleManager.unlock ();
    }
    
    private int receive ()
    {
        ARNETWORKAL_MANAGER_RETURN_ENUM result = ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_DEFAULT;
        
        if (!bleManager.readData(array))
        {
            result = ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_NO_DATA_AVAILABLE;
        }
        
        return result.getValue();
    }
    
    private DataPop popFrame ()
    {
        DataPop dataPop = new DataPop();
        
        ARNETWORKAL_MANAGER_RETURN_ENUM result = ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_DEFAULT;
        BluetoothGattCharacteristic characteristic = null;
        
        /* -- get a Frame of the receiving buffer -- */
        /* if the receiving buffer not contain enough data for the frame head */
        if (array.size() == 0)
        {
            result = ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_BUFFER_EMPTY;
        }
        
        if (result == ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_DEFAULT)
        {
            characteristic = array.get (0);
            if (characteristic.getValue().length == 0)
            {
                result = ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_BAD_FRAME;
            }
        }
        
        if (result == ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_DEFAULT)
        {
            byte[] currentFrame = characteristic.getValue();
            
            /* get id */
            String frameIdString = characteristic.getUuid().toString().substring(4, 8);
            int frameId = Integer.parseInt(frameIdString, 16);
            
            if (result == ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_DEFAULT)
            {
                /* Get the frame from the buffer */
                dataPop.setId(frameId);
                dataPop.setData(currentFrame);
                
                bwCurrentDown += currentFrame.length;
            }
        }
        
        if (result != ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_BUFFER_EMPTY)
        {
            array.remove (0);
        }
        
        dataPop.setResult (result.getValue());
        return dataPop;
    }
    
    private int pushFrame (int type, int id, int seq, int size, byte[] byteData)
    {
        ARNETWORKAL_MANAGER_RETURN_ENUM result = ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_DEFAULT;
        
        if (result == ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_DEFAULT)
        {
            /* The size of byteData is checked before by the JNI */
            byte[] data = new byte[byteData.length + ARNETWORKAL_BLENETWORK_HEADER_SIZE];
            
            /* Add frame type */
            data[0] = (byte) type;
            
            /* Add frame seq */
            data[1] = (byte) seq;
            
            /* Add frame data */
            System.arraycopy(byteData, 0, data, 2, byteData.length);
            
            /* Get the good characteristic */
            BluetoothGattCharacteristic characteristicToSend = null;
            if (type == ARNETWORKAL_FRAME_TYPE_ENUM.ARNETWORKAL_FRAME_TYPE_ACK.getValue())
            {
                characteristicToSend = recvService.getCharacteristics().get(id);
            }
            else
            {
                characteristicToSend = sendService.getCharacteristics().get(id);
            }
            
            /* write the data */
            if (!bleManager.writeData(data, characteristicToSend))
            {
                result = ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_BAD_FRAME;
            }
            else
            {
                bwCurrentUp += data.length;
            }
        }
        
        return result.getValue();
    }
    
    private class DataPop
    {
        int id;
        byte[] data;
        int result;
        
        DataPop()
        {
            this.id = 0;
            this.data = null;
            this.result = ARNETWORKAL_MANAGER_RETURN_ENUM.ARNETWORKAL_MANAGER_RETURN_DEFAULT.getValue();
        }
        
        void setData (byte[] data)
        {
            this.data = data;
        }
        
        void setId (int id)
        {
            this.id = id;
        }
        
        void setResult (int result)
        {
            this.result = result;
        }
        
        byte[] getData ()
        {
            return data;
        }
        
        int getId ()
        {
            return id;
        }
        
        int getResult ()
        {
            return result;
        }
        
    }
    
    public void onBLEDisconnect ()
    {
        nativeJNIOnDisconect (jniARNetworkALBLENetwork);
    }
}

