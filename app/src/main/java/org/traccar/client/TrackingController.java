/*
 * Copyright 2015 Anton Tananaev (anton.tananaev@gmail.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.traccar.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;

public class TrackingController implements PositionProvider.PositionListener, NetworkManager.NetworkHandler {

    private static final String TAG = TrackingController.class.getSimpleName();
    private static final int RETRY_DELAY = 30 * 1000;
    private static final int WAKE_LOCK_TIMEOUT = 120 * 1000;

    private boolean isOnline;
    private boolean isWaiting;

    private Context context;
    private Handler handler;
    private SharedPreferences preferences;

    private String url;

    private PositionProvider positionProvider;
    private DatabaseHelper databaseHelper;
    private NetworkManager networkManager;
    private BluetoothController bluetoothController;

    private PowerManager.WakeLock wakeLock;

    public String extAttribute = "";
    private int delay = 30000; //milliseconds
    private boolean stopBluetoothScan;
    private boolean readAttributeFromFile = false;
    private String externalAttributefilePath = "";
    private boolean scanNearbyBluetoothDevices = false;
    private int scanBluetoothEveryMinutes = 10;
    private int scanningBluetoothTime = 1;

    private void lock() {
        wakeLock.acquire(WAKE_LOCK_TIMEOUT);
    }

    private void unlock() {
        if (wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    public TrackingController(Context context) {
        this.context = context;
        handler = new Handler();
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        positionProvider = new PositionProvider(context, this);
        databaseHelper = new DatabaseHelper(context);
        networkManager = new NetworkManager(context, this);
        bluetoothController = new BluetoothController(context);
        isOnline = networkManager.isOnline();

        url = preferences.getString(MainFragment.KEY_URL, context.getString(R.string.settings_url_default_value));

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());

        //getUserPreferences();
    }

    public void start() {
        if (isOnline) {
            read();
        }
        try {
            positionProvider.startUpdates();
            if(scanNearbyBluetoothDevices) {
                stopBluetoothScan = false;
                startBluetoothScan();
            }
        } catch (SecurityException e) {
            Log.w(TAG, e);
        }
        networkManager.start();
    }

    public void stop() {
        networkManager.stop();
        try {
            positionProvider.stopUpdates();
            stopBluetoothScan=true;
        } catch (SecurityException e) {
            Log.w(TAG, e);
        }
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public void onPositionUpdate(Position position) {
        StatusActivity.addMessage(context.getString(R.string.status_location_update));
        if (position != null) {
            write(position);
        }
    }

    @Override
    public void onNetworkUpdate(boolean isOnline) {
        int message = isOnline ? R.string.status_network_online : R.string.status_network_offline;
        StatusActivity.addMessage(context.getString(message));
        if (!this.isOnline && isOnline) {
            read();
        }
        this.isOnline = isOnline;
    }

    //
    // State transition examples:
    //
    // write -> read -> send -> delete -> read
    //
    // read -> send -> retry -> read -> send
    //

    private void log(String action, Position position) {
        if (position != null) {
            action += " (" +
                    "id:" + position.getId() +
                    " time:" + position.getTime().getTime() / 1000 +
                    " lat:" + position.getLatitude() +
                    " lon:" + position.getLongitude() + ")";
        }
        Log.d(TAG, action);
    }

    private void write(Position position) {
        log("write", position);
        lock();
        Toast.makeText(context,"write: ",Toast.LENGTH_LONG).show();
        String externalAttributes = getAllExternalAttributes();
        databaseHelper.insertPositionAsync(position, externalAttributes ,new DatabaseHelper.DatabaseHandler<Void>() {
            @Override
            public void onComplete(boolean success, Void result) {
                if (success) {
                    if (isOnline && isWaiting) {
                        read();
                        isWaiting = false;
                        Toast.makeText(context,"write_done: ",Toast.LENGTH_LONG).show();
                    }
                }
                unlock();
            }
        });
    }

    private void read() {
        log("read", null);
        lock();
        Toast.makeText(context,"read: ",Toast.LENGTH_LONG).show();
        databaseHelper.selectPositionAsync(new DatabaseHelper.DatabaseHandler<Position>() {
            @Override
            public void onComplete(boolean success, Position result) {
                if (success) {
                    if (result != null) {
                        if (result.getDeviceId().equals(preferences.getString(MainFragment.KEY_DEVICE, null))) {
                            send(result);
                            Toast.makeText(context,"read_done: ",Toast.LENGTH_LONG).show();
                        } else {
                            delete(result);
                        }
                    } else {
                        isWaiting = true;
                    }
                } else {
                    retry();
                }
                unlock();
            }
        });
    }

    private void delete(Position position) {
        log("delete", position);
        lock();
        databaseHelper.deletePositionAsync(position.getId(), new DatabaseHelper.DatabaseHandler<Void>() {
            @Override
            public void onComplete(boolean success, Void result) {
                if (success) {
                    read();
                } else {
                    retry();
                }
                unlock();
            }
        });
    }

    private void send(final Position position) {
        log("send", position);
        lock();
        Toast.makeText(context,"send: ",Toast.LENGTH_LONG).show();
        String request = ProtocolFormatter.formatRequest(url, position);
        RequestManager.sendRequestAsync(request, new RequestManager.RequestHandler() {
            @Override
            public void onComplete(boolean success) {
                if (success) {
                    delete(position);
                    Toast.makeText(context,"send_done: ",Toast.LENGTH_LONG).show();
                } else {
                    StatusActivity.addMessage(context.getString(R.string.status_send_fail));
                    retry();
                }
                unlock();
            }
        });
    }

    private void retry() {
        log("retry", null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isOnline) {
                    read();
                }
            }
        }, RETRY_DELAY);
    }

    private void startBluetoothScan(){
        handler.postDelayed(new Runnable(){
            public void run(){
                bluetoothController.startScan();
                handler.postDelayed(this, scanBluetoothEveryMinutes*1000*60);
                if(stopBluetoothScan)
                    handler.removeCallbacks(this);
            }
        }, scanBluetoothEveryMinutes*1000*60);
    }

    private String getNearbyBluetoothDevices(){
        ArrayList<BTLE_Device> mBTDevicesArrayList = bluetoothController.mBTDevicesArrayList;
        String name = "";
        String address = "";
        String rssi = "";
        for(BTLE_Device device : mBTDevicesArrayList){
            if(name=="")
                name = '"'+device.getName()+'"';
            else
                name += " ,\""+device.getName()+'"';
            if(address=="")
                address = "\""+device.getAddress()+"\"";
            else
                address += " ,\""+device.getAddress()+"\"";
            if(rssi=="")
                rssi =  " ,"+Integer.toString(device.getRSSI());
            else
                rssi +=  " ,"+Integer.toString(device.getRSSI()) ;

        }
        String nearbyBluetoothDevices = '"'+"BluetoothDevices\": {\"address\":[" + address + "], \"name\":[" + name + "], \"rssi\":[" + rssi + "]}";
       return nearbyBluetoothDevices;
    }

    private String getAllExternalAttributes(){
        String allExternalAtrribute = "";
        getUserPreferences();
        if(scanNearbyBluetoothDevices) {
            String nearbyBluetoothDevices = getNearbyBluetoothDevices();
            allExternalAtrribute += "\"nearbyBluetoothDevices\":{"+nearbyBluetoothDevices+"}";
            Toast.makeText(context, "nearbyBluetoothDevices: " + nearbyBluetoothDevices, Toast.LENGTH_LONG).show();
        }
        if(readAttributeFromFile) {
            String externalAttributeFromFile = getExternalAttributesFromFile();
            if(allExternalAtrribute != "")
                allExternalAtrribute +=",";
            allExternalAtrribute +=" \"externalAttributeFromFile\": {" + externalAttributeFromFile + "}";
        }
        return allExternalAtrribute;
    }

    private String getExternalAttributesFromFile(){
        String line = null;

        try {
            String filepath = externalAttributefilePath;
            if(filepath=="")
                return "";
            FileInputStream fileInputStream = new FileInputStream (new File(filepath));

            InputStreamReader inputStreamReader = new InputStreamReader(fileInputStream);
            BufferedReader bufferedReader = new BufferedReader(inputStreamReader);
            StringBuilder stringBuilder = new StringBuilder();

            while ( (line = bufferedReader.readLine()) != null )
            {
                stringBuilder.append(line + System.getProperty("line.separator"));
            }
            fileInputStream.close();
            line = stringBuilder.toString();

            bufferedReader.close();
        }
        catch(FileNotFoundException ex) {
            Log.d(TAG, ex.getMessage());
        }
        catch(IOException ex) {
            Log.d(TAG, ex.getMessage());
        }
        extAttribute=line;
        return extAttribute;
    }

    private void getUserPreferences(){
        if(preferences.getBoolean(MainFragment.KEY_READ_ATTRIBUTES_FROM_FILE, true))
            readAttributeFromFile = true;
        else
            readAttributeFromFile = false;
        if(preferences.getBoolean(MainFragment.KEY_SCAN_NEARBY_BT_DEVICES, true))
            scanNearbyBluetoothDevices = true;
        else
            scanNearbyBluetoothDevices = false;

        scanBluetoothEveryMinutes = Integer.parseInt(preferences.getString(MainFragment.KEY_SCAN_BT_EVERY_MINUTES, "10"));
        scanningBluetoothTime =  Integer.parseInt(preferences.getString(MainFragment.KEY_SCANNING_BT_TIME, "1"));
        externalAttributefilePath = preferences.getString(MainFragment.KEY_FILEPATH, "");
    }

}
