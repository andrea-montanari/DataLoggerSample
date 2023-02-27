package com.movesense.samples.dataloggersample;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.google.gson.Gson;
import com.movesense.mds.Mds;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsResponseListener;
import com.polidea.rxandroidble2.RxBleClient;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;

public class DataLoggerActivity extends AppCompatActivity
        implements
        AdapterView.OnItemClickListener,
        Spinner.OnItemSelectedListener
{
    private static final boolean AUTOMATIC_LOGGING_MANAGEMENT = true;
    private static final int RECORDING_TIME = 20;     // Minutes
//    private static Thread thread;
//    private static boolean logging;
//    private static boolean logCreated = false;
//    private static boolean logEntryFetched = false;

    private static final String URI_MDS_LOGBOOK_ENTRIES = "suunto://MDS/Logbook/{0}/Entries";
    private static final String URI_MDS_LOGBOOK_DATA= "suunto://MDS/Logbook/{0}/ById/{1}/Data";

    private static final String URI_LOGBOOK_ENTRIES = "suunto://{0}/Mem/Logbook/Entries";
    private static final String URI_DATALOGGER_STATE = "suunto://{0}/Mem/DataLogger/State";
    private static final String URI_DATALOGGER_CONFIG = "suunto://{0}/Mem/DataLogger/Config";

    public static final int LOG_ID_MSG = 123;

    static DataLoggerActivity s_INSTANCE = null;
    private static final String LOG_TAG = DataLoggerActivity.class.getSimpleName();

    public static final String SERIAL = "serial";
    public static final String MAC = "mac";
    String connectedSerial;
    String connectedMAC;
    static private RxBleClient mBleClient;
    static Mds mMds;

    private DataLoggerState mDLState;
    private String mDLConfigPath;
    private TextView mDataLoggerStateTextView;

    private ListView mLogEntriesListView;
    private static ArrayList<MdsLogbookEntriesResponse.LogEntry> mLogEntriesArrayList = new ArrayList<>();
    ArrayAdapter<MdsLogbookEntriesResponse.LogEntry> mLogEntriesArrayAdapter;
    private TextView tvLogId;

    // Service variables
    private Intent loggerServiceIntent;
    LoggerForegroundService mService;
    boolean mBound = false;

    // Variable for updating UI while service is running
    Thread threadUpdateUI;
    boolean updateUI = false;

    public static final String SCHEME_PREFIX = "suunto://";

    private Handler logIdHandler;

    private Mds getMDS() {return MainActivity.mMds;}

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        s_INSTANCE = this;

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_datalogger);

        // Init state UI
        mDataLoggerStateTextView = (TextView)findViewById(R.id.textViewDLState);
        tvLogId = (TextView) findViewById(R.id.textViewCurrentLogID);

        // Init Log list
        mLogEntriesListView = (ListView)findViewById(R.id.listViewLogbookEntries);
        mLogEntriesArrayAdapter = new ArrayAdapter<MdsLogbookEntriesResponse.LogEntry>(this,
                android.R.layout.simple_list_item_1, mLogEntriesArrayList);
        mLogEntriesListView.setAdapter(mLogEntriesArrayAdapter);
        mLogEntriesListView.setOnItemClickListener(this);

        Spinner pathSpinner = (Spinner)findViewById(R.id.path_spinner);
        pathSpinner.setOnItemSelectedListener(this);
        mPathSelectionSetInternally = true;
        pathSpinner.setSelection(0);

        logIdHandler = new Handler();

        // Bind to service
        loggerServiceIntent = new Intent(this, LoggerForegroundService.class);
        bindService(loggerServiceIntent, connection, Context.BIND_AUTO_CREATE);

        // Initialize Movesense MDS library
        if (mMds == null) {
            mMds = Mds.builder().build(this);
        }

        // Find serial in opening intent
        Intent intent = getIntent();
        connectedSerial = intent.getStringExtra(SERIAL);
        connectedMAC = intent.getStringExtra(MAC);

        updateDataLoggerUI();

        fetchDataLoggerConfig();

        refreshLogList();
    }


    private RxBleClient getBleClient() {
        // Init RxAndroidBle (Ble helper library) if not yet initialized
        if (mBleClient == null)
        {
            mBleClient = RxBleClient.create(this);
        }

        return mBleClient;
    }


    //region Service methods
    // ---------------------------------------------------------------------------------------------
    private ServiceConnection connection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName className,
                                       IBinder service) {
            // We've bound to LocalService, cast the IBinder and get LocalService instance
            LoggerForegroundService.LocalBinder binder = (LoggerForegroundService.LocalBinder) service;
            mService = binder.getService();
            mBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            mBound = false;
        }
    };

    @RequiresApi(api = Build.VERSION_CODES.O)
    private void startService() {
        startForegroundService(loggerServiceIntent);
        updateUI = true;

        threadUpdateUI = new Thread() {
            @Override
            public void run() {
                while (updateUI) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }

                    int logId = mService.getLogId();
                    logIdHandler.post(() -> {
                        mLogEntriesArrayList.clear();
                        MdsLogbookEntriesResponse entriesResponse = mService.getEntriesResponse();
                        if (entriesResponse != null) {
                            for (MdsLogbookEntriesResponse.LogEntry logEntry : entriesResponse.logEntries) {
                                Log.d(LOG_TAG, "Entry: " + logEntry);
                                mLogEntriesArrayList.add(logEntry);
                            }
                            mLogEntriesArrayAdapter.notifyDataSetChanged();
                            tvLogId.setText("" + logId);
                        }
                    });
                }
            }
        };
        threadUpdateUI.start();
    }

    private void stopService() {
        mService.setLogging(false);
        if (mBound) {
            unbindService(connection);
        }
        mBound = false;
        updateUI = false;
        this.stopService(loggerServiceIntent);
    }
    // ---------------------------------------------------------------------------------------------
    //endregion

    private void updateDataLoggerUI() {
        Log.d(LOG_TAG, "updateDataLoggerUI() state: " + mDLState + ", path: " + mDLConfigPath);

        mDataLoggerStateTextView.setText(mDLState != null ? mDLState.toString() : "--");

        findViewById(R.id.buttonStartLogging).setEnabled(mDLState != null && mDLConfigPath!=null);
        findViewById(R.id.buttonStopLogging).setEnabled(mDLState != null);

        if (mDLState != null) {
            if (mDLState.content == 2) {
                findViewById(R.id.buttonStartLogging).setVisibility(View.VISIBLE);
                findViewById(R.id.buttonStopLogging).setVisibility(View.GONE);
            }
            if (mDLState.content == 3) {
                findViewById(R.id.buttonStopLogging).setVisibility(View.VISIBLE);
                findViewById(R.id.buttonStartLogging).setVisibility(View.GONE);
            }
        }
    }

    private void configureDataLogger() {
        // Access the DataLogger/Config
        String configUri = MessageFormat.format(URI_DATALOGGER_CONFIG, connectedSerial);

        // Create the config object
        DataLoggerConfig.DataEntry[] entries = {new DataLoggerConfig.DataEntry(mDLConfigPath)};
        DataLoggerConfig config = new DataLoggerConfig(new DataLoggerConfig.Config(new DataLoggerConfig.DataEntries(entries)));
        String jsonConfig = new Gson().toJson(config,DataLoggerConfig.class);

        Log.d(LOG_TAG, "Config request: " + jsonConfig);
        getMDS().put(configUri, jsonConfig, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                updateDataLoggerUI();
                Log.i(LOG_TAG, "PUT config succesful: " + data);
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "PUT DataLogger/Config returned error: " + e);
            }
        });
    }

    private void fetchDataLoggerState() {
        // Access the DataLogger/State
        String stateUri = MessageFormat.format(URI_DATALOGGER_STATE, connectedSerial);

        getMDS().get(stateUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "GET state successful: " + data);

                mDLState = new Gson().fromJson(data, DataLoggerState.class);
                updateDataLoggerUI();
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "GET DataLogger/State returned error: " + e);
            }
        });
    }

    private boolean mPathSelectionSetInternally = false;
    private void fetchDataLoggerConfig() {
        // Access the DataLogger/State
        String stateUri = MessageFormat.format(URI_DATALOGGER_CONFIG, connectedSerial);
        mDLConfigPath=null;

        getMDS().get(stateUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "GET DataLogger/Config succesful: " + data);

                DataLoggerConfig config = new Gson().fromJson(data, DataLoggerConfig.class);
                Spinner spinner = (Spinner)findViewById(R.id.path_spinner);
                for (DataLoggerConfig.DataEntry de : config.content.dataEntries.dataEntry)
                {
                    Log.d(LOG_TAG, "DataEntry: " + de.path);

                    String dePath = de.path;
                    if (dePath.contains("{"))
                    {
                        dePath = dePath.substring(0,dePath.indexOf('{'));
                        Log.d(LOG_TAG, "dePath: " + dePath);

                    }
                    // Start searching for item from 1 since 0 is the default text for empty selection
                    for (int i=1; i<spinner.getAdapter().getCount(); i++)
                    {
                        String path = spinner.getItemAtPosition(i).toString();
                        Log.d(LOG_TAG, "spinner.path["+ i+"]: " + path);
                        // Match the beginning (skip the part with samplerate parameter)
                        if (path.toLowerCase().startsWith(dePath.toLowerCase()))
                        {
                            mPathSelectionSetInternally = true;
                            Log.d(LOG_TAG, "mPathSelectionSetInternally to #"+ i);

                            spinner.setSelection(i);
                            mDLConfigPath =path;
                            break;
                        }
                    }
                }
                // If no match found, set to first item (/Meas/Acc/13)
                if (mDLConfigPath == null)
                {
                    Log.d(LOG_TAG, "no match found, set to first item");

                    spinner.setSelection(0);
                }

                fetchDataLoggerState();
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "GET DataLogger/Config returned error: " + e);
                fetchDataLoggerState();
            }
        });
    }

//    private void automaticLoggingManagement(int logId) {
//        Activity currentActivity = this;
//
//        thread = new Thread() {
//            @Override public void run() {
//                long startTime;
//                long endTime;
//                int id = logId;
//                while (logging) {
//                    startTime = SystemClock.elapsedRealtime();
//                    if (id > 2) {
//                        fetchLogEntry(id - 1);
//                    }
//                    else {
//                        logEntryFetched = true;
//                    }
//                    // Wait for new log to be created and for data to be fetched before disconnecting the device
//                    while (!logCreated || !logEntryFetched) {
//                        Log.d("BLE_CONNECTION_HANDLER", "Waiting for data retrieval...");
//                        try {
//                            thread.sleep(50);
//                        } catch (InterruptedException e) {
//                            e.printStackTrace();
//                        }
//                    }
//                    logCreated = false;
//                    logEntryFetched = false;
//                    endTime = SystemClock.elapsedRealtime();
//                    try {
//                        Log.d("MAC", "Connected MAC: " + connectedMAC);
//                        BLEConnectionHandler.disconnectBLEDevice(connectedMAC);
//                        long timeToSleep = 3000 - (endTime - startTime);
//                        Log.d("Time", "Time difference: " + (endTime - startTime));
//                        Log.d("Time", "Time to sleep: " + timeToSleep);
//                        if (timeToSleep < 0) {
//                            thread.sleep(0);
//                        }
//                        else {
//                            thread.sleep(timeToSleep);
//                        }
//                        BLEConnectionHandler.connectBLEDevice(connectedMAC, currentActivity);
//                        while (!BLEConnectionHandler.getDeviceIsConnected(connectedMAC)) {
//                            Log.d("BLE_CONNECTION_HANDLER", "Waiting for connection...");
//                            thread.sleep(100);
//                        }
//                        Log.d("BLE_CONNECTION_HANDLER", "After while. Connection completed.");
//                        Log.d("Time", "Time after: " + System.nanoTime()/1000);
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                    }
//                    // Check again for when the STOP LOGGING button is pressed
//                    if (logging) {
//                        createNewLog();
//                        id++;
//                    }
//                }
//            }
//        };
//        thread.start();
//    }

//    private void createNewLogSync() {
//        // TODO: fix this -> it blocks the main thread.
//        createNewLog();
//        while (!logCreated) {
//            try {
//                Thread.sleep(100);
//            } catch (InterruptedException e) {
//                e.printStackTrace();
//            }
//        }
//        logCreated = false;
//    }

    private void setDataLoggerState(final boolean bStartLogging) {
        Log.d("THREAD_STOP_LOGGING", "setDataLoggerState");
        // Access the DataLogger/State
        String stateUri = MessageFormat.format(URI_DATALOGGER_STATE, connectedSerial);
        final Context me = this;
        int newState = bStartLogging ? 3 : 2;   // 3 = logging, 2 = stop logging
        Log.d("THREAD_STOP_LOGGING", "setDataLoggerState, new state");
        String payload = "{\"newState\":" + newState + "}";
        getMDS().put(stateUri, payload, new MdsResponseListener() {
            @RequiresApi(api = Build.VERSION_CODES.O)
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "PUT DataLogger/State state succesful: " + data);
                Log.d("THREAD_STOP_LOGGING", "setDataLoggerState: SUCCESS");

                mDLState.content = newState;
                updateDataLoggerUI();
                // Update log list if we stopped
                if (!bStartLogging)
                    refreshLogList();

                String textId = "";

                if (AUTOMATIC_LOGGING_MANAGEMENT)
                    mService.setLogging(true);
                    if (bStartLogging) {

                        try {
                            textId = ((TextView)findViewById(R.id.textViewCurrentLogID)).getText().toString();
                        }
                        catch (Exception e) {
                            Log.d(LOG_TAG, "Exception: " + e);
                            Log.d(LOG_TAG, "Id not selected");
                            createNewLog();

                        }
                        Log.d(LOG_TAG, "Id: " + textId);
                        if (textId.equals("--")) {
                            createNewLog();
                        }
                        textId = ((TextView)findViewById(R.id.textViewCurrentLogID)).getText().toString();
                        int logId = Integer.parseInt(textId);

                        if (!mBound) {
                            bindService(loggerServiceIntent, connection, Context.BIND_AUTO_CREATE);
                        }
                        // Wait for binding if it hasn't yet completed, then setting some essential
                        // variables and starting service
                        new Thread() {
                            @Override public void run() {
                                while (!mBound) {
                                    Log.d(LOG_TAG, "Waiting for binding.");
                                    try {
                                        Thread.sleep(100);
                                    } catch (InterruptedException e) {
                                        e.printStackTrace();
                                    }
                                }
                                mService.setConnectedMAC(connectedMAC);
                                mService.setLogId(logId);
                                startService();
                            }
                        }.start();
//                        automaticLoggingManagement(logId);
                    }
                    else {
                        textId = ((TextView)findViewById(R.id.textViewCurrentLogID)).getText().toString();
                        if (textId.equals("--")) {
                            createNewLog();
                        }
                        textId = ((TextView)findViewById(R.id.textViewCurrentLogID)).getText().toString();
                        int logId = Integer.parseInt(textId);
                        Log.d("Fetch", "logId: " + logId);
                        fetchLogEntry(logId);
                    }
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "PUT DataLogger/State returned error: " + e);

                if (e.getStatusCode()==423 && bStartLogging) {
                    // Handle "LOCKED" from NAND variant
                    new AlertDialog.Builder(me)
                            .setTitle("DataLogger Error")
                            .setMessage("Can't start logging due to error 'locked'. Possibly too low battery on the sensor.")
                            .show();

                }

            }
        });
    }

    public void onStartLoggingClicked(View view) {
        setDataLoggerState(true);
//        startService();
    }

    public void onStopLoggingClicked(View view) {
        Activity currentActivity = this;
        Log.d("THREAD_STOP_LOGGING", "onStopLogging");
        stopService();
        Log.d("THREAD_STOP_LOGGING", "Auto thread stopped");

        Thread threadStopLogging = new Thread() {
            @Override
            public void run() {
                Log.d("THREAD_STOP_LOGGING", "Thread started");
                if (!BLEConnectionHandler.getDeviceIsConnected(connectedMAC)) {
                    BLEConnectionHandler.connectBLEDevice(connectedMAC, currentActivity);
                    Log.d("THREAD_STOP_LOGGING", "Connecting");
                    while (!BLEConnectionHandler.getDeviceIsConnected(connectedMAC)) {
                        Log.d("BLE_CONNECTION_HANDLER", "Waiting for connection...");
                        try {
                            Log.d("THREAD_STOP_LOGGING", "Waiting for connection...");
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
                // Wait for data retrieval to finish
                while (mService.getThreadIsRunning()) {
                    Log.d("THREAD_STOP_LOGGING", "Thread active");
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                setDataLoggerState(false);
                Log.d("THREAD_STOP_LOGGING", "setDataLoggerState called.");
            }
        };
        threadStopLogging.start();
//        stopService();
    }

    private void refreshLogList() {
        // Access the /Logbook/Entries
        String entriesUri = MessageFormat.format(URI_MDS_LOGBOOK_ENTRIES, connectedSerial);

        getMDS().get(entriesUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "GET LogEntries succesful: " + data);

                MdsLogbookEntriesResponse entriesResponse = new Gson().fromJson(data, MdsLogbookEntriesResponse.class);
                findViewById(R.id.buttonRefreshLogs).setEnabled(true);

                mLogEntriesArrayList.clear();
                for (MdsLogbookEntriesResponse.LogEntry logEntry : entriesResponse.logEntries) {
                    Log.d(LOG_TAG, "Entry: " + logEntry);
                    mLogEntriesArrayList.add(logEntry);
                }
                mLogEntriesArrayAdapter.notifyDataSetChanged();
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "GET LogEntries returned error: " + e);
            }
        });
    }

    private MdsLogbookEntriesResponse.LogEntry findLogEntry(final int id)
    {
        MdsLogbookEntriesResponse.LogEntry entry = null;
        for (MdsLogbookEntriesResponse.LogEntry e : mLogEntriesArrayList) {
            if ((e.id == id)) {
                entry = e;
                break;
            }
        }
        return entry;
    }

    public void onRefreshLogsClicked(View view) {
        refreshLogList();
    }

    public void onEraseLogsClicked(View view) {
        AlertDialog.Builder builder;
        builder = new AlertDialog.Builder(this, android.R.style.Theme_Material_Dialog_Alert);
        builder.setTitle("Erase Logs")
                .setMessage("Are you sure you want to wipe all logbook entries?")
                .setPositiveButton(android.R.string.yes, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // continue with delete
                        eraseAllLogs();
                    }
                })
                .setNegativeButton(android.R.string.no, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {
                        // do nothing
                    }
                })
                .setIcon(android.R.drawable.ic_dialog_alert)
                .show();

    }

    private void eraseAllLogs() {
        // Access the Logbook/Entries resource
        String entriesUri = MessageFormat.format(URI_LOGBOOK_ENTRIES, connectedSerial);

        findViewById(R.id.buttonStartLogging).setEnabled(false);
        findViewById(R.id.buttonStopLogging).setEnabled(false);
        findViewById(R.id.buttonRefreshLogs).setEnabled(false);

        getMDS().delete(entriesUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "DELETE LogEntries succesful: " + data);
                refreshLogList();
                updateDataLoggerUI();
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "DELETE LogEntries returned error: " + e);
                refreshLogList();
                updateDataLoggerUI();
            }
        });
    }

    @Override
    protected void onDestroy() {
        Log.d(LOG_TAG,"onDestroy()");

        // Leave datalogger logging
        DataLoggerActivity.s_INSTANCE = null;

        super.onDestroy();
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        if (adapterView != findViewById(R.id.path_spinner))
            return;
        Log.d(LOG_TAG, "Path selected: " + adapterView.getSelectedItem().toString() + ", i: "+ i);
        mDLConfigPath = (i==0) ? null : adapterView.getSelectedItem().toString();
        // Only update config if UI selection was not set by the code (result of GET /Config)
        if (mDLConfigPath != null &&
                !mPathSelectionSetInternally &&
                adapterView.getSelectedItemPosition()>0)
        {
            Log.d(LOG_TAG, "Calling configureDataLogger:" + mDLConfigPath);
            configureDataLogger();
        }
        mPathSelectionSetInternally = false;
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
        Log.i(LOG_TAG, "Nothing selected");

    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        if (parent != findViewById(R.id.listViewLogbookEntries))
            return;

        MdsLogbookEntriesResponse.LogEntry entry = mLogEntriesArrayList.get(position);
        fetchLogEntry(entry.id);
    }

    private void fetchLogEntry(final int id) {
        Log.d("BLE_CONNECTION_HANDLER", "fetchLogEntry");

//        findViewById(R.id.headerProgress).setVisibility(View.VISIBLE);
        // GET the /MDS/Logbook/Data proxy
        refreshLogList();

        String logDataUri = MessageFormat.format(URI_MDS_LOGBOOK_DATA, connectedSerial, id);
        final Context me = this;
        final long logGetStartTimestamp = new Date().getTime();

        getMDS().get(logDataUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(final String data) {
                Log.d("BLE_CONNECTION_HANDLER", "fetchLogEntry: success");
                mService.setLogEntryFetched(true);
                MdsLogbookEntriesResponse.LogEntry entry = findLogEntry(id);


                if (Looper.myLooper() == Looper.getMainLooper()) {
                    Log.d("Fetch", "Main thread executing fetch");
                }


                final String filename = new StringBuilder()
                            .append("MovesenseLog_").append(id).append(" ")
                            .append(entry.getDateStr()).toString();

//                if (AUTOMATIC_LOGGING_MANAGEMENT) {
//                    try {
//                        saveLogToFileAutomatic(filename, data);
//                    } catch (FileNotFoundException e) {
//                        Log.e(LOG_TAG, "Exception: " + e);
//                    }
//                }
//                else {
                    final long logGetEndTimestamp = new Date().getTime();
                    final float speedKBps = (float)entry.size / (logGetEndTimestamp-logGetStartTimestamp) / 1024.0f * 1000.f;
                    Log.i("Fetch", "GET Log Data succesful. size: " + entry.size + ", speed: " + speedKBps);

                    final String message = new StringBuilder()
                            .append("Downloaded log #").append(id).append(" from the Movesense sensor.")
                            .append("\n").append("Size:  ").append(entry.size).append(" bytes")
                            .append("\n").append("Speed: ").append(speedKBps).append(" kB/s")
                            .append("\n").append("\n").append("File will be saved in location you choose.")
                            .toString();

                    AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(me)
                            .setTitle("Save Log Data")
                            .setMessage(message).setPositiveButton("Save to Phone", new DialogInterface.OnClickListener() {
                                        @Override
                                        public void onClick(DialogInterface dialog, int which) {
                                            saveLogToFile(filename, data);
                                        }
                                    }
                            );
//
//                    findViewById(R.id.headerProgress).setVisibility(View.GONE);
                    alertDialogBuilder.show();
//                }
            }

            @Override
            public void onError(MdsException e) {
                Log.e("Fetch", "GET Log Data returned error: " + e);
//                findViewById(R.id.headerProgress).setVisibility(View.GONE);
            }
        });
    }

    private String mDataToWriteFile;
    private static int CREATE_FILE = 1;

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        if (requestCode == CREATE_FILE
                && resultCode == Activity.RESULT_OK) {
            // The result data contains a URI for the document or directory that
            // the user selected.
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                writeDataToFile(uri, mDataToWriteFile);
                mDataToWriteFile = null;
            }
        }
    }
    private void writeDataToFile(Uri uri, String data)
    {
        // Save data to the file
        Log.d(LOG_TAG, "Writing data to uri: " + uri);

        try
        {
            OutputStream out = getContentResolver().openOutputStream(uri);
            OutputStreamWriter myOutWriter = new OutputStreamWriter(out);

            // Write in pieces in case the file is big
            final int BLOCK_SIZE= 4096;
            for (int startIdx=0;startIdx<data.length();startIdx+=BLOCK_SIZE) {
                int endIdx = Math.min(data.length(), startIdx + BLOCK_SIZE);
                myOutWriter.write(data.substring(startIdx, endIdx));
            }

            myOutWriter.flush();
            myOutWriter.close();

            out.flush();
            out.close();
        }
        catch (IOException e)
        {
            Log.e(LOG_TAG, "File write failed: ", e);
        }

        // re-scan files so that they get visible in Windows
        //MediaScannerConnection.scanFile(this, new String[]{file.getAbsolutePath()}, null, null);
    }

    private void saveLogToFileAutomatic(String filename, String data) throws FileNotFoundException {
        Log.d("Fetch", "saveLofToFileAutomatic");
        mDataToWriteFile = data;
        // Add extension to filename if it doesn't have yet
        if (!filename.endsWith(".json"))
        {
            filename = filename + ".json";
        }

        File filePath = null;
        FileOutputStream fos = null;
        try {
            filename = filename.replace(" ", "_").replace(":", "-");
            filePath = new File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    filename
            );
            Log.d("Fetch", "dir.getname(): " + filePath.getCanonicalPath() );
//            if (filePath == null || !filePath.mkdirs()) {
//                Log.e(LOG_TAG, "Directory not created");
//            }

            fos = new FileOutputStream(filePath);
            fos.write(data.getBytes(StandardCharsets.UTF_8));
            Toast.makeText(getApplicationContext(), "Saved to " + filePath.getName() + "/" + filename, Toast.LENGTH_LONG).show();
            Log.d("Fetch", "Toast executed");
        } catch (FileNotFoundException e) {
            Log.e(LOG_TAG, "Exception: " + e);
        } catch (IOException e) {
            Log.e(LOG_TAG, "Exception: " + e);
        } finally {
            if (fos != null)
            try {
                fos.close();
            } catch (IOException e) {
                Log.e(LOG_TAG, "Exception: " + e);
            }
        }
        Log.d("Fetch", "File written");
    }

    private void saveLogToFile(String filename, String data) {
        mDataToWriteFile = data;
        // Add extension to filename if it doesn't have yet
        if (!filename.endsWith(".json"))
        {
            filename = filename + ".json";
        }

        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        intent.putExtra(Intent.EXTRA_TITLE, filename);

        startActivityForResult(intent, CREATE_FILE);
    }

    public void onCreateNewLogClicked(View view) {
        createNewLog();
    }

    private void createNewLog() {
        // Access the Logbook/Entries resource
        String entriesUri = MessageFormat.format(URI_LOGBOOK_ENTRIES, connectedSerial);

        Log.d("BLE_CONNECTION_HANDLER", "on createNewLog");

        getMDS().post(entriesUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(LOG_TAG, "POST LogEntries succesful: " + data);
                IntResponse logIdResp = new Gson().fromJson(data, IntResponse.class);

                TextView tvLogId = (TextView)findViewById(R.id.textViewCurrentLogID);
                tvLogId.setText("" + logIdResp.content);
            }

            @Override
            public void onError(MdsException e) {
                Log.e(LOG_TAG, "POST LogEntries returned error: " + e);
                TextView tvLogId = (TextView)findViewById(R.id.textViewCurrentLogID);
                tvLogId.setText("##");
            }
        });

    }
}
