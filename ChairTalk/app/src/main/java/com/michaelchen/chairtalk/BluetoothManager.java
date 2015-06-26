package com.michaelchen.chairtalk;

import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Handler;
import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

/**
 * Created by michael on 4/23/15.
 */
class BluetoothManager {

    private BluetoothLeService mBluetoothLeService;

    private BluetoothGattCharacteristic characteristic;
    private String mDeviceAddress;
    private MainActivity activity;
    private boolean mConnected;
    public final String TAG = "BluetoothManager";
    public static final String SERV_UUID = "0000ffe0-0000-1000-8000-00805f9b34fb";
    public static final String CHAR_UUID = "0000ffe1-0000-1000-8000-00805f9b34fb";
    public static final String MAC_KEY = "bluetooth_mac";

    private final ServiceConnection mServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.d("MainAct initBLE", "Unable to initialize Bluetooth");
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }
        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    public boolean isConnected() {
        return mConnected;
    }

    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
            } /*else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                activity.disconnectBluetoothManager();
                activity.setBluetoothConnected(false);
            } */else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                readData(intent.getByteArrayExtra(BluetoothLeService.EXTRA_DATA));
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                initBle();
            }
        }
    };

    BluetoothManager(final MainActivity activity, String deviceAddress) {
        this.activity = activity;
        mDeviceAddress = deviceAddress;
        Intent gattServiceIntent = new Intent(activity, BluetoothLeService.class);
        activity.getBaseContext().startService(gattServiceIntent);
        activity.bindService(gattServiceIntent, mServiceConnection, activity.BIND_AUTO_CREATE);
        onResume();
    }

    private void initBle() {
        if (mBluetoothLeService != null) {
            String uuid;
            List<BluetoothGattService> services = mBluetoothLeService.getSupportedGattServices();
            for (BluetoothGattService service : services) {
                uuid = service.getUuid().toString();
                if (uuid.equals(SERV_UUID)) {
                    List<BluetoothGattCharacteristic> gattCharacteristics =
                            service.getCharacteristics();
                    for(BluetoothGattCharacteristic characteristic: gattCharacteristics) {
                        String charUuid = characteristic.getUuid().toString();
                        if (charUuid.equals(CHAR_UUID)) {
                            this.characteristic = characteristic;
                            startNotifications();
                            return;
                        }
                    }
                }
            }
        }
    }

    void startNotifications() {
        if (characteristic != null && mBluetoothLeService != null) {
            mBluetoothLeService.setCharacteristicNotification(characteristic, true);
        }
    }

    void onResume() {
        activity.registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    void onDestroy() {
        onPause();
        if (mBluetoothLeService != null) {
            mBluetoothLeService.disconnect();
            mBluetoothLeService.close();
        }
        if (mServiceConnection != null) {
            activity.unbindService(mServiceConnection);
        }
        mBluetoothLeService = null;
    }

    void onPause() {
        activity.unregisterReceiver(mGattUpdateReceiver);
    }

    void readData(byte[] result) {
        // TODO: hand result to MainActivity
        Log.e(TAG, "read: " + result);
        writeTime();
        activity.setBleStatus(result);
    }

    void writeTime() {
        if (!activity.synchronized_with_server) {
            System.out.println("Can't write current time.");
            activity.synchronizeTimeAsync();
            return;
        }
        System.out.println("Writing the current time.");
        int seconds = (int) (0.5 + activity.get_time());
        System.out.printf("Current time is %d.\n", seconds);
        byte[] buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(seconds).array();
        byte[] toWrite = new byte[5];
        for (int i = 0; i < buf.length; i++) {
            toWrite[i] = buf[i];
        }
        toWrite[4] = 0;
        writeData(toWrite);

    }

    boolean writeData(byte[] data) {
        System.out.println("Writing data");
        if (characteristic == null || !mConnected || mBluetoothLeService == null) {
            System.out.println(mConnected);
            return false;
        }

        characteristic.setValue(data);
        mBluetoothLeService.writeCharacteristic(characteristic);
        return true;
    }


    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    void disconnect() {
        onDestroy();
        characteristic = null;
        if (mBluetoothLeService != null) {
            activity.unbindService(mServiceConnection);
        }

    }
}
