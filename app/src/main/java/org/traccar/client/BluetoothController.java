package org.traccar.client;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Context;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.widget.Button;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;

public class BluetoothController {
    private final static String TAG = MainActivity.class.getSimpleName();

    public static final int REQUEST_ENABLE_BT = 1;
    public static final int BTLE_SERVICES = 2;

    private HashMap<String, BTLE_Device> mBTDevicesHashMap;
    private ArrayList<BTLE_Device> mBTDevicesArrayList;
    private Context context;

    private BroadcastReceiver_BTState mBTStateUpdateReceiver;
    private Scanner_BTLE mBTLeScanner;
    public BluetoothController(Context context) {
        this.context = context;
        if (!context.getPackageManager().hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)) {
            //Toast.makeText(getApplicationContext(), "BLE not supported", Toast.LENGTH_SHORT).show();
            Utils.toast(context, "BLE not supported");
        }
        mBTStateUpdateReceiver = new BroadcastReceiver_BTState(context);
        mBTLeScanner = new Scanner_BTLE(context, 15000, -175, this);
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

        Utils.toast(context, "AddDevice:"+address);
        //adapter.notifyDataSetChanged();
    }

    public void startScan(){

        mBTDevicesArrayList.clear();
        mBTDevicesHashMap.clear();
        //adapter.notifyDataSetChanged();

        mBTLeScanner.start();
    }

    public void stopScan() {

        mBTLeScanner.stop();
    }
}
