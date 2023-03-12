package com.movesense.samples.dataloggersample;

import android.app.Activity;
import android.content.Intent;
import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.movesense.mds.Mds;
import com.movesense.mds.MdsConnectionListener;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsResponseListener;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleDevice;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;

public class BLEConnectionHandler {

    // BleClient singleton
    static private RxBleClient mBleClient;

    private static final String URI_TIME = "suunto://{0}/Time";
    public static final String LOG_TAG = "BLE_CONNECTION_HANDLER";

    private static ArrayList<MyScanResult> mScanResArrayList = new ArrayList<>();
    private static ArrayAdapter<MyScanResult> mScanResArrayAdapter;

    private static boolean connected = false;

//    public static void initBLEConnectionHandler(
//            ArrayList<MyScanResult> mScanResArrayList,
//            ArrayAdapter<MyScanResult> mScanResArrayAdapter) {
//
//        mScanResArrayAdapter = mScanResArrayAdapter;
//
//    }

    private static Mds getMDS() {return MainActivity.mMds;}

    private static RxBleClient getBleClient(Activity callerActivity) {
        // Init RxAndroidBle (Ble helper library) if not yet initialized
        if (mBleClient == null)
        {
            mBleClient = RxBleClient.create(callerActivity);
        }

        return mBleClient;
    }

    public static void connectBLEDevice(
            ArrayList<MyScanResult> mScanResArrayList,
            ArrayAdapter<MyScanResult> mScanResArrayAdapter,
            String deviceMACAddress,
            Activity callerActivity
    ) {
        BLEConnectionHandler.mScanResArrayList = mScanResArrayList;
        BLEConnectionHandler.mScanResArrayAdapter = mScanResArrayAdapter;

        RxBleDevice bleDevice = getBleClient(callerActivity).getBleDevice(deviceMACAddress);
        Log.d(LOG_TAG, "Connecting to BLE device: " + bleDevice.getMacAddress());
        connected = false;
        getMDS().connect(bleDevice.getMacAddress(), new MdsConnectionListener() {

            @Override
            public void onConnect(String s) {
                Log.d(LOG_TAG, "onConnect:" + s);
            }

            @Override
            public void onConnectionComplete(String macAddress, String serial) {
                Log.d(LOG_TAG, "onConnectionComplete");
                for (MyScanResult sr : BLEConnectionHandler.mScanResArrayList) {
                    if (sr.macAddress.equalsIgnoreCase(macAddress)) {
                        sr.markConnected(serial);
                        break;
                    }
                }
                Log.d(LOG_TAG, "After scan results");
                BLEConnectionHandler.mScanResArrayAdapter.notifyDataSetChanged();

                Log.d(LOG_TAG, "After notifydatesetchanged. Serial: " + serial);

                // Set sensor clock
                setCurrentTimeToSensor(serial);
                Log.d(LOG_TAG, "After set current time");

                if (callerActivity != null) {
                    Log.d(LOG_TAG, "Caller activity name: " + callerActivity.getLocalClassName());
                    if (callerActivity.getLocalClassName().equals("MainActivity")) {
                        // Open the DataLoggerActivity
                        Intent intent = new Intent(callerActivity, DataLoggerActivity.class);
                        intent.putExtra(DataLoggerActivity.SERIAL, serial);
                        intent.putExtra(DataLoggerActivity.MAC, macAddress);
                        callerActivity.startActivity(intent);
                    }
                }

                connected = true;
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "onError:" + e);

                showConnectionError(e, callerActivity);
                connected = false;
            }

            @Override
            public void onDisconnect(String bleAddress) {
                Log.d(LOG_TAG, "onDisconnect: " + bleAddress);
                for (MyScanResult sr : BLEConnectionHandler.mScanResArrayList) {
                    if (bleAddress.equals(sr.macAddress)) {
                        // Unsubscribe all from possible
//                        if (sr.connectedSerial != null &&
//                                DataLoggerActivity.s_INSTANCE != null &&
//                                sr.connectedSerial.equals(DataLoggerActivity.s_INSTANCE.connectedSerial)) {
//                            DataLoggerActivity.s_INSTANCE.finish();
//                        }
                        sr.markDisconnected();
                    }
                }
                BLEConnectionHandler.mScanResArrayAdapter.notifyDataSetChanged();
                connected = false;
            }
        });
    }

    public static void connectBLEDevice(String deviceMACAddress, Activity callerActivity) {
        BLEConnectionHandler.connectBLEDevice(
                BLEConnectionHandler.mScanResArrayList,
                BLEConnectionHandler.mScanResArrayAdapter,
                deviceMACAddress,
                callerActivity
        );
    }

    public static boolean getDeviceIsConnected(String macAddress) {
        for (MyScanResult sr : BLEConnectionHandler.mScanResArrayList) {
            if (sr.macAddress.equalsIgnoreCase(macAddress) && sr.isConnected()) {
                return true;
            }
        }
        return false;
//        return connected;
    }

    public static void disconnectBLEDevice(String macAddress) {
        getMDS().disconnect(macAddress);
    }

    private static void setCurrentTimeToSensor(String serial) {
        Log.d(LOG_TAG, "Set time sensor. Serial: " + serial);
        String timeUri = MessageFormat.format(URI_TIME, serial);
        Log.d(LOG_TAG, "Set time sensor. URI: " + timeUri);
        String payload = "{\"value\":" + (new Date().getTime() * 1000) + "}";
        Log.d(LOG_TAG, "Set time sensor. Payload: " + payload);
        getMDS().put(timeUri, payload, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "PUT /Time succesful: " + data);
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "PUT /Time returned error: " + e);
            }
        });
    }

    private static void showConnectionError(MdsException e, Activity callerActivity) {
        AlertDialog.Builder builder = new AlertDialog.Builder(callerActivity)
                .setTitle("Connection Error:")
                .setMessage(e.getMessage());

        builder.create().show();
    }

}
