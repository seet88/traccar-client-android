package org.traccar_gospogied.client;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.widget.Toast;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import java.lang.reflect.Method;
import java.util.UUID;
import android.content.Context;
import android.preference.PreferenceManager;

public class ArduinoBTExchanger {

    private final static String TAG = MainActivity.class.getSimpleName();
    final int RECIEVE_MESSAGE = 1;        // Status  for Handler
    private BluetoothAdapter btAdapter = null;
    private BluetoothSocket btSocket = null;
    private StringBuilder sb = new StringBuilder();
    private String bTArduinoName = "HC-05";
    private Context context;
    private SharedPreferences preferences;
    public Handler h;
    public String messageFromArduino = "";
    public boolean isConnectionLost = false;
    public  int  counter = 1;

    private ConnectedThread mConnectedThread;

    // SPP UUID service
    private static final UUID MY_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // MAC-address of Bluetooth module (you must edit this line)
    private static String address = "98:D3:91:FD:7A:CA";

    public ArduinoBTExchanger(Context context) {
        this.context = context;

        preferences = PreferenceManager.getDefaultSharedPreferences(context);

        //scanningBluetoothTime =  Integer.parseInt(preferences.getString(MainFragment.KEY_SCANNING_BT_TIME, "1"));


        //context.registerReceiver(mBTStateUpdateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

    }



    public void tryCommunicate() {
        Toast.makeText(context, "Uruchamiam tryComunicate" , Toast.LENGTH_LONG).show();   // update TextView
        h = new Handler() {
            public void handleMessage(android.os.Message msg) {
                switch (msg.what) {
                    case RECIEVE_MESSAGE:                                                   // if receive massage
                        byte[] readBuf = (byte[]) msg.obj;
                        String strIncom = new String(readBuf, 0, msg.arg1);                 // create string from bytes array
                        sb.append(strIncom);                                                // append string
                        int endOfLineIndex = sb.indexOf("Y");                            // determine the end-of-line
                        if (endOfLineIndex > 0) {                                            // if end-of-line,
                            String sbprint = sb.substring(0, endOfLineIndex);               // extract string
                            sb.delete(0, sb.length());                                      // and clear
                             //Toast.makeText(context, "sbprint:" + sbprint, Toast.LENGTH_LONG).show();   // update TextView
                            counter ++;
                            messageFromArduino = sbprint;
                            messageFromArduino +=", "+ '"'+"hCounter"+'"'+":" + counter ;
                        }
                        //Log.i(TAG, "...String:"+ sb.toString() +  "Byte:" + msg.arg1 + "...");
                        break;
                }
            };
        };

        btAdapter = BluetoothAdapter.getDefaultAdapter();       // get Bluetooth adapter
        checkBTState();
        address = pairedDevicesList(); //fill address with paired arduino BT device
        //Toast.makeText(context, "adress" + address, Toast.LENGTH_LONG).show();
        createConnectThreat();
        //Toast.makeText(context, "after createTreat",Toast.LENGTH_LONG).show();
    }

    public String getMessageFromArduino(){
        return messageFromArduino;
    }

    private String pairedDevicesList(){
        for (BluetoothDevice pairedDevice : btAdapter.getBondedDevices()) {
            if (pairedDevice.getName().contains(bTArduinoName)) {
                //Log.i(TAG, "\tDevice Name: " +  pairedDevice.getName());
                //Log.i(TAG, "\tDevice MAC: " + pairedDevice.getAddress());

                return pairedDevice.getAddress();
            }
        }
        return null;
    }

    private BluetoothSocket createBluetoothSocket(BluetoothDevice device) throws IOException {
        if(Build.VERSION.SDK_INT >= 10){
            try {
                final Method m = device.getClass().getMethod("createInsecureRfcommSocketToServiceRecord", new Class[] { UUID.class });
                return (BluetoothSocket) m.invoke(device, MY_UUID);
            } catch (Exception e) {
                Log.e(TAG, "Could not create Insecure RFComm Connection",e);
            }
        }
        return  device.createRfcommSocketToServiceRecord(MY_UUID);
    }
    public void closeConnectionToArduino(){
        Toast.makeText(context, "closeConnectionToArduino", Toast.LENGTH_LONG).show();   // update TextView
        if(btSocket != null){
            try {
                Toast.makeText(context, "try close socket", Toast.LENGTH_LONG).show();
                btSocket.close();
            } catch (IOException e) {
                Toast.makeText(context, "fail close socket", Toast.LENGTH_LONG).show();
                //errorExit("Fatal Error", "Cannot close connection " + e.getMessage() + ".");
            }

        }
    }

    public boolean isConnectedTreadAlive(){
        return mConnectedThread.isAlive();
    }


    private void createConnectThreat(){


        // Set up a pointer to the remote node using it's address.
        if(address.length()>0) {
            BluetoothDevice device = btAdapter.getRemoteDevice(address);

            // Two things are needed to make a connection:
            //   A MAC address, which we got above.
            //   A Service ID or UUID.  In this case we are using the
            //     UUID for SPP.

            try {
                btSocket = createBluetoothSocket(device);
            } catch (IOException e) {
                errorExit("Fatal Error", "In onResume() and socket create failed: " + e.getMessage() + ".");
            }

            // Discovery is resource intensive.  Make sure it isn't going on
            // when you attempt to connect and pass your message.
            btAdapter.cancelDiscovery();

            // Establish the connection.  This will block until it connects.
            Log.i(TAG, "...Connecting...");
            try {
                btSocket.connect();
                Log.i(TAG, "....Connection ok...");
            } catch (IOException e) {
                try {
                    btSocket.close();
                } catch (IOException e2) {
                    errorExit("Fatal Error", "In onResume() and unable to close socket during connection failure" + e2.getMessage() + ".");
                }
            }

            // Create a data stream so we can talk to server.
            Log.i(TAG, "...Create Socket...");

            mConnectedThread = new ConnectedThread(btSocket);
            mConnectedThread.start();
        }else
            Log.i(TAG, "no connected adnruino device");

    }

    private void checkBTState() {
        return;
        // Check for Bluetooth support and then check to make sure it is turned on
        // Emulator doesn't support Bluetooth and will return null
        /*
        if(btAdapter==null) {
            errorExit("Fatal Error", "Bluetooth not support");
        } else {
            if (btAdapter.isEnabled()) {
                Log.d(TAG, "...Bluetooth ON...");
            } else {
                //Prompt user to turn on Bluetooth
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, 1);
            }
        }
        */
    }

    private void errorExit(String title, String message){
        Toast.makeText(context, title + " - " + message, Toast.LENGTH_LONG).show();
    }

    private class ConnectedThread extends Thread {
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket) {
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) { }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[256];  // buffer store for the stream
            int bytes; // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);        // Get number of bytes and message in "buffer"
                    h.obtainMessage(RECIEVE_MESSAGE, bytes, -1, buffer).sendToTarget();     // Send to message queue Handler --tmp
                    isConnectionLost = false;
                } catch (IOException e) {
                    isConnectionLost = true;
                    messageFromArduino = "";
                    Toast.makeText(context, "cannot connect: "+e.getMessage(),Toast.LENGTH_LONG).show();
                    break;
                }
            }
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(String message) {
            Log.d(TAG, "...Data to send: " + message + "...");
            byte[] msgBuffer = message.getBytes();
            try {
                mmOutStream.write(msgBuffer);
            } catch (IOException e) {
                Log.d(TAG, "...Error data send: " + e.getMessage() + "...");
            }
        }
    }
}
