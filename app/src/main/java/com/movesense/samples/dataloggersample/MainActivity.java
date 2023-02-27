package com.movesense.samples.dataloggersample;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import com.movesense.mds.Mds;
import com.movesense.mds.MdsConnectionListener;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsResponseListener;
import com.polidea.rxandroidble2.RxBleClient;
import com.polidea.rxandroidble2.RxBleDevice;
import com.polidea.rxandroidble2.scan.ScanSettings;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;

import io.reactivex.disposables.Disposable;

public class MainActivity extends AppCompatActivity implements AdapterView.OnItemLongClickListener, AdapterView.OnItemClickListener  {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();
    private static final int MY_PERMISSIONS_REQUEST = 1;

    // MDS singleton
    static Mds mMds;
    public static final String URI_CONNECTEDDEVICES = "suunto://MDS/ConnectedDevices";
    public static final String URI_EVENTLISTENER = "suunto://MDS/EventListener";
    public static final String SCHEME_PREFIX = "suunto://";
    private static final String URI_TIME = "suunto://{0}/Time";

    // BleClient singleton
    static private RxBleClient mBleClient;

    //
    // UI
    private ListView mScanResultListView;
    private static ArrayList<MyScanResult> mScanResArrayList = new ArrayList<>();
    ArrayAdapter<MyScanResult> mScanResArrayAdapter;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Init Scan UI
        mScanResultListView = (ListView)findViewById(R.id.listScanResult);
        mScanResArrayAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_list_item_1, mScanResArrayList);
        mScanResultListView.setAdapter(mScanResArrayAdapter);
        mScanResultListView.setOnItemLongClickListener(this);
        mScanResultListView.setOnItemClickListener(this);

        // Make sure we have all the permissions this app needs
        requestNeededPermissions();

        // Initialize Movesense MDS library
        initMds();
    }

    private RxBleClient getBleClient() {
        // Init RxAndroidBle (Ble helper library) if not yet initialized
        if (mBleClient == null)
        {
            mBleClient = RxBleClient.create(this);
        }

        return mBleClient;
    }

    private void initMds() {
        if (mMds == null) {
            mMds = Mds.builder().build(this);
        }
    }

    void requestNeededPermissions()
    {
        // Here, thisActivity is the current activity
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {

            // No explanation needed, we can request the permissions.
            ActivityCompat.requestPermissions(this,
                new String[]{
                        Manifest.permission.ACCESS_COARSE_LOCATION,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                },
                MY_PERMISSIONS_REQUEST);
        }
    }

    Disposable mScanSubscription;
    public void onScanClicked(View view) {
        findViewById(R.id.buttonScan).setVisibility(View.GONE);
        findViewById(R.id.buttonScanStop).setVisibility(View.VISIBLE);

        // Start with empty list
        mScanResArrayList.clear();
        mScanResArrayAdapter.notifyDataSetChanged();

        mScanSubscription = getBleClient().scanBleDevices(
                new ScanSettings.Builder()
                        // .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY) // change if needed
                        // .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES) // change if needed
                        .build()
                // add filters if needed
        )
                .subscribe(
                        scanResult -> {
                            Log.d(LOG_TAG,"scanResult: " + scanResult);

                            // Process scan result here. filter movesense devices.
                            if (scanResult.getBleDevice()!=null &&
                                    scanResult.getBleDevice().getName() != null &&
                                    scanResult.getBleDevice().getName().startsWith("Movesense")) {

                                // replace if exists already, add otherwise
                                MyScanResult msr = new MyScanResult(scanResult);
                                if (mScanResArrayList.contains(msr))
                                    mScanResArrayList.set(mScanResArrayList.indexOf(msr), msr);
                                else
                                    mScanResArrayList.add(0, msr);

                                mScanResArrayAdapter.notifyDataSetChanged();
                            }
                        },
                        throwable -> {
                            Log.e(LOG_TAG,"scan error: " + throwable);
                            // Handle an error here.

                            // Re-enable scan buttons, just like with ScanStop
                            onScanStopClicked(null);
                        }
                );
    }

    public void onScanStopClicked(View view) {
        if (mScanSubscription != null)
        {
            mScanSubscription.dispose();
            mScanSubscription = null;
        }

        findViewById(R.id.buttonScan).setVisibility(View.VISIBLE);
        findViewById(R.id.buttonScanStop).setVisibility(View.GONE);
    }


    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= mScanResArrayList.size())
            return;

        MyScanResult device = mScanResArrayList.get(position);
        if (!device.isConnected()) {
            // Stop scanning
            onScanStopClicked(null);

            // And connect to the device
            BLEConnectionHandler.connectBLEDevice(
                    mMds,
                    mScanResArrayList,
                    mScanResArrayAdapter,
                    device.macAddress,
                    this
            );
//            connectBLEDevice(device);
        }
    }



    @Override
    public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
        if (position < 0 || position >= mScanResArrayList.size())
            return false;

        MyScanResult device = mScanResArrayList.get(position);

        Log.i(LOG_TAG, "Disconnecting from BLE device: " + device.macAddress);
        BLEConnectionHandler.disconnectBLEDevice(device.macAddress);
//        mMds.disconnect(device.macAddress);

        return true;
    }

//    public void connectDevice() {
//        MyScanResult device = null;
//        for (MyScanResult sr: mScanResArrayList
//        ) {
//            if (sr.connectedSerial == connectedSerial)
//                device = sr;
//        }
//        connectBLEDevice(device);
//    }

    public void connectBLEDevice(MyScanResult device) {
        RxBleDevice bleDevice = getBleClient().getBleDevice(device.macAddress);
        final Activity me = this;
        Log.d("BLE_Main", "Connecting to BLE device: " + bleDevice.getMacAddress());
        mMds.connect(bleDevice.getMacAddress(), new MdsConnectionListener() {

            @Override
            public void onConnect(String s) {
                Log.d("BLE_Main", "onConnect:" + s);
            }

            @Override
            public void onConnectionComplete(String macAddress, String serial) {
                Log.d("BLE_Main", "onConnectionComplete");
                for (MyScanResult sr : mScanResArrayList) {
                    if (sr.macAddress.equalsIgnoreCase(macAddress)) {
                        sr.markConnected(serial);
                        break;
                    }
                }
                mScanResArrayAdapter.notifyDataSetChanged();

                // Set sensor clock
                setCurrentTimeToSensor(serial);

                // Open the DataLoggerActivity
                Intent intent = new Intent(me, DataLoggerActivity.class);
                intent.putExtra(DataLoggerActivity.SERIAL, serial);
                intent.putExtra(DataLoggerActivity.MAC, macAddress);
                startActivity(intent);
            }

            @Override
            public void onError(MdsException e) {
                Log.e("BLE_Main", "onError:" + e);

                showConnectionError(e);
            }

            @Override
            public void onDisconnect(String bleAddress) {
                Log.d("BLE_Main", "onDisconnect: " + bleAddress);
                for (MyScanResult sr : mScanResArrayList) {
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
                mScanResArrayAdapter.notifyDataSetChanged();
            }
        });
    }


    private void setCurrentTimeToSensor(String serial) {
        String timeUri = MessageFormat.format(URI_TIME, serial);
        String payload = "{\"value\":" + (new Date().getTime() * 1000) + "}";
        mMds.put(timeUri, payload, new MdsResponseListener() {
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

    private void showConnectionError(MdsException e) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("Connection Error:")
                .setMessage(e.getMessage());

        builder.create().show();
    }

}
