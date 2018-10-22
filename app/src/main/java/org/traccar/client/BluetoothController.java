package org.traccar.client;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;
import android.widget.Button;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;

public class BluetoothController {
    private final static String TAG = MainActivity.class.getSimpleName();

    public static final int REQUEST_ENABLE_BT = 1;
    public static final int BTLE_SERVICES = 2;

    private HashMap<String, BTLE_Device> mBTDevicesHashMap;
    public ArrayList<BTLE_Device> mBTDevicesArrayList;
    public ArrayList<BTLE_Device> mBTDevicesHistoryArrayList;
    private Context context;

    private SharedPreferences preferences;
    private BroadcastReceiver_BTState mBTStateUpdateReceiver;
    private Scanner_BTLE mBTLeScanner;
    private int scanningBluetoothTime = 1;

    public BluetoothController(Context context) {
        this.context = context;
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            Utils.toast(context, "BLE not supported");
        }
        preferences = PreferenceManager.getDefaultSharedPreferences(context);
        mBTStateUpdateReceiver = new BroadcastReceiver_BTState(context);

        scanningBluetoothTime =  Integer.parseInt(preferences.getString(MainFragment.KEY_SCANNING_BT_TIME, "1"));
        mBTLeScanner = new Scanner_BTLE(context, scanningBluetoothTime*1000, -175, this);
        mBTDevicesHashMap = new HashMap<>();
        mBTDevicesArrayList = new ArrayList<>();

        context.registerReceiver(mBTStateUpdateReceiver, new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED));

    }

    public void addDevice(BluetoothDevice device, int rssi) {

        String address = device.getAddress();
        if (!mBTDevicesHashMap.containsKey(address)) {
            BTLE_Device btleDevice = new BTLE_Device(device);
            btleDevice.setRSSI(rssi);

            mBTDevicesHashMap.put(address, btleDevice);
            mBTDevicesArrayList.add(btleDevice);
        }
        else {
            mBTDevicesHashMap.get(address).setRSSI(rssi);
        }
        //Toast.makeText(context,"address: "+address,Toast.LENGTH_LONG).show();
        //adapter.notifyDataSetChanged();
    }

    public void startScan(){
        mBTDevicesHistoryArrayList = mBTDevicesArrayList;
        mBTDevicesArrayList.clear();
        mBTDevicesHashMap.clear();
        //adapter.notifyDataSetChanged();

        mBTLeScanner.start();
    }

    public void stopScan() {

        mBTLeScanner.stop();
    }
}
