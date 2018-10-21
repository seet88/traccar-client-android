package org.traccar.client;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Handler;
import android.widget.Toast;

public class Scanner_BTLE {
    private BluetoothController bc;
    private final Context context;

    private BluetoothAdapter mBluetoothAdapter;
    private boolean mScanning = false;
    private Handler mHandler;

    private long scanPeriod;
    private int signalStrength;

    public Scanner_BTLE(Context context, long scanPeriod, int signalStrength) {
        this.context = context;
       // Toast.makeText(context,"middle_Scanner_BTLE: ",Toast.LENGTH_LONG).show();

        mHandler = new Handler();

        this.scanPeriod = scanPeriod;
        this.signalStrength = signalStrength;

        final BluetoothManager bluetoothManager =
                (BluetoothManager) context.getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = bluetoothManager.getAdapter();
      //  Toast.makeText(context,"end_Scanner_BTLE: ",Toast.LENGTH_LONG).show();
    }

    public boolean isScanning() {
        return mScanning;
    }

    public void start() {
       /*seet if (!Utils.checkBluetooth(mBluetoothAdapter)) {
            //seet Utils.requestUserBluetooth(context);
            bc.stopScan();
        }
        else { */
            scanLeDevice(true);
       // }
    }

    public void stop() {
        scanLeDevice(false);
    }

    // If you want to scan for only specific types of peripherals,
    // you can instead call startLeScan(UUID[], BluetoothAdapter.LeScanCallback),
    // providing an array of UUID objects that specify the GATT services your app supports.
    private void scanLeDevice(final boolean enable) {
        if (enable && !mScanning) {
            Utils.toast(context, "Starting BLE scan...");

            // Stops scanning after a pre-defined scan period.

            mHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    Utils.toast(context, "Stopping BLE scan...");

                    mScanning = false;
                     mBluetoothAdapter.stopLeScan(mLeScanCallback);

                    //seet bc.stopScan();
                }
            }, 10000);
            mScanning = true;

             mBluetoothAdapter.startLeScan(mLeScanCallback);
//            mBluetoothAdapter.startLeScan(uuids, mLeScanCallback);
        }
        else {
            mScanning = false;
           //seet mBluetoothAdapter.stopLeScan(mLeScanCallback);
        }

        Toast.makeText(context,"scanLeDevice: ",Toast.LENGTH_LONG).show();
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback =
            new BluetoothAdapter.LeScanCallback() {

                @Override
                public void onLeScan(final BluetoothDevice device, int rssi, byte[] scanRecord) {

                    final int new_rssi = rssi;
                    if (rssi > signalStrength) {
                        mHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                //bc.addDevice(device, new_rssi);
                                Toast.makeText(context,"scan: ",Toast.LENGTH_LONG).show();
                                Utils.toast(context, "CallBackdevice"+device.getAddress());
                            }
                        });
                    }
                }
            };
}
