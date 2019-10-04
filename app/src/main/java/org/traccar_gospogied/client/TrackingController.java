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
package org.traccar_gospogied.client;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.PowerManager;
import android.preference.PreferenceManager;
import android.util.Log;

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
    private ArduinoBTExchanger arduinoBTExchanger;

    private PowerManager.WakeLock wakeLock;

    public String extAttribute = "";
    private int delay = 30000; //milliseconds
    private boolean stopBluetoothScan;
    private boolean readAttributeFromFile = false;
    private String externalAttributeFilePath = "";
    private boolean scanNearbyBluetoothDevices = false;
    private int scanBluetoothEveryMinutes = 1;
    private boolean communicateWithArduino = false;

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
        arduinoBTExchanger = new ArduinoBTExchanger(context);
        isOnline = networkManager.isOnline();

        url = preferences.getString(MainFragment.KEY_URL, context.getString(R.string.settings_url_default_value));

        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, getClass().getName());

    }

    public void start() {
        if (isOnline) {
            read();
        }
        try {
            getUserPreferences();
            positionProvider.startUpdates();
            if(scanNearbyBluetoothDevices) {
                stopBluetoothScan = false;
                startBluetoothScan();
            }
            if(communicateWithArduino)
                startArduinoComunication();
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
            arduinoBTExchanger.closeConnectionToArduino();
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
        String externalAttributes = getAllExternalAttributes();

        databaseHelper.insertPositionAsync(position, externalAttributes ,new DatabaseHelper.DatabaseHandler<Void>() {
            @Override
            public void onComplete(boolean success, Void result) {
                if (success) {
                    if (isOnline && isWaiting) {
                        read();
                        isWaiting = false;
                    }
                }
                unlock();
            }
        });
    }

    private void read() {
        log("read", null);
        lock();
        databaseHelper.selectPositionAsync(new DatabaseHelper.DatabaseHandler<Position>() {
            @Override
            public void onComplete(boolean success, Position result) {
                if (success) {
                    if (result != null) {
                        if (result.getDeviceId().equals(preferences.getString(MainFragment.KEY_DEVICE, null))) {
                            send(result);
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
        String request = ProtocolFormatter.formatRequest(url, position);
        RequestManager.sendRequestAsync(request, new RequestManager.RequestHandler() {
            @Override
            public void onComplete(boolean success) {
                if (success) {
                    delete(position);
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

    private void startArduinoComunication(){
        //first scan on start then loop with delay
        arduinoBTExchanger.tryCommunicate();

    }

    private void startBluetoothScan(){
        //first scan on start then loop with delay
        bluetoothController.startScan();

        handler.postDelayed(new Runnable(){
            public void run(){
                bluetoothController.startScan();
                handler.postDelayed(this, scanBluetoothEveryMinutes*1000*60);
                if(stopBluetoothScan)
                    handler.removeCallbacks(this);
            }
        }, scanBluetoothEveryMinutes*1000*60);
    }

    private String getNearbyBluetoothDevices(boolean onlyiTags){
        ArrayList<BTLE_Device> mBTDevicesArrayList = bluetoothController.mBTDevicesHistoryArrayList;
        String name = "";
        String address = "";
        String rssi = "";
        for(BTLE_Device device : mBTDevicesArrayList){
            if(onlyiTags && address.contains("FF:FF:") || !onlyiTags) {
                if (name == "")
                    name = '"' + device.getName() + '"';
                else
                    name += " ," + '"' + device.getName() + '"';
                if (address == "")
                    address = '"' + device.getAddress() + '"';
                else
                    address += " ," + '"' + device.getAddress() + '"';
                if (rssi == "")
                    rssi = Integer.toString(device.getRSSI());
                else
                    rssi += " ," + Integer.toString(device.getRSSI());
            }
        }
        String nearbyBluetoothDevices ="";
        if(address.length()<1700)
            nearbyBluetoothDevices = '"'+"BluetoothDevices"+'"'+": {"+'"'+"address"+'"'+":[" + address + "]";
        else {
            nearbyBluetoothDevices = '"' + "BluetoothDevices"+'"'+": {"+'"'+"address"+'"'+":[" + address.substring(0, 1700) + "] }";
            return nearbyBluetoothDevices;
        }
        if(nearbyBluetoothDevices.length()<750)
            nearbyBluetoothDevices += ", "+'"'+"rssi"+'"'+":[" + rssi + "] }";
        else
            return nearbyBluetoothDevices + "}";
        //if(nearbyBluetoothDevices.length()<1100)
        //if want add any other property from device bluetooth
        //nearbyBluetoothDevices += '"'+"BluetoothDevices\": {\"address\":[" + address + "], \"name\":[" + name + "], \"rssi\":[" + rssi + "]}";
       return nearbyBluetoothDevices;
    }

    private String prepareNearbyluetoothDevices(boolean onlyiTags){
        String nearbyBluetoothDevices = getNearbyBluetoothDevices(onlyiTags);
        return "{"+nearbyBluetoothDevices+"}";
    }

    private String getArduinoAttributes(){
        String attribute = arduinoBTExchanger.messageFromArduino;
        return attribute;
    }

    private String getAllExternalAttributes(){
        String allExternalAttribute = "[";
        getUserPreferences();
        //if attributes from files is longer then xxx  then only adress in FF
        if(scanNearbyBluetoothDevices) {
            allExternalAttribute += prepareNearbyluetoothDevices(false);
            //allExternalAttribute += "nearbyBluetoothDevices"+'"'+":{"+nearbyBluetoothDevices+"}";
            //Toast.makeText(context, "nearbyBluetoothDevices: " + nearbyBluetoothDevices, Toast.LENGTH_LONG).show();
        }
        if(readAttributeFromFile) {
            String externalAttributeFromFile = getExternalAttributesFromFile();
            if(allExternalAttribute.length()+externalAttributeFromFile.length()>1900 && scanNearbyBluetoothDevices)
                allExternalAttribute = "["+prepareNearbyluetoothDevices(true);
            if(allExternalAttribute.length()>5)
                allExternalAttribute +=",";
            allExternalAttribute +=" {" + externalAttributeFromFile + "}";
            //allExternalAttribute +="externalAttributeFromFile"+'"'+": {" + externalAttributeFromFile + "}";
        }
        if(communicateWithArduino){
            String externalAttributeFromArduino = getArduinoAttributes();
            if(allExternalAttribute.length()>5)
                allExternalAttribute +=",";
            allExternalAttribute += " {" + externalAttributeFromArduino + "}";
        }
        allExternalAttribute += "]";
        return allExternalAttribute;
    }

    private String getExternalAttributesFromFile(){
        String line = null;

        try {
            String filepath = externalAttributeFilePath;
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
        if(preferences.getBoolean(MainFragment.KEY_COMMUNICATE_WITH_ARDUINO,true))
            communicateWithArduino = true;
        else
            communicateWithArduino = false;
        scanBluetoothEveryMinutes = Integer.parseInt(preferences.getString(MainFragment.KEY_SCAN_BT_EVERY_MINUTES, "10"));
        externalAttributeFilePath = preferences.getString(MainFragment.KEY_FILEPATH, "");
    }

}
