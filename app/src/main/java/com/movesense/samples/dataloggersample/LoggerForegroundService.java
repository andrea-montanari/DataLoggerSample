package com.movesense.samples.dataloggersample;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.support.annotation.Nullable;

import androidx.annotation.RequiresApi;

import android.util.Log;

import com.google.gson.Gson;
import com.movesense.mds.Mds;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsResponseListener;
import com.movesense.samples.dataloggersample.model.ExtendedEnergyGetModel;

import java.text.MessageFormat;
import java.util.Date;

public class LoggerForegroundService extends Service {

    private static final int ONGOING_NOTIFICATION_ID = 1;
    public static final String TAG = "LoggerForegroundService";
    private static final String URI_DATALOGGER_CONFIG = "suunto://{0}/Mem/DataLogger/Config";
    private static final String URI_LOGBOOK_ENTRIES = "suunto://{0}/Mem/Logbook/Entries";
    private static final String URI_DATALOGGER_STATE = "suunto://{0}/Mem/DataLogger/State";
    private static final String URI_MDS_LOGBOOK_ENTRIES = "suunto://MDS/Logbook/{0}/Entries";
    private static final String URI_MDS_LOGBOOK_DATA= "suunto://MDS/Logbook/{0}/ById/{1}/Data";
    private static final String BATTERY_PATH_GET_EXTENDED = "/System/Energy";

    public static final boolean GET_BATTERY_STATUS = false;

//    private ListView mLogEntriesListView;
//    private static ArrayList<MdsLogbookEntriesResponse.LogEntry> mLogEntriesArrayList = new ArrayList<>();
//    ArrayAdapter<MdsLogbookEntriesResponse.LogEntry> mLogEntriesArrayAdapter;

    private Notification notification;
    private DataLoggerState mDLState;
    private String connectedSerial;
    private String mDLConfigPath;
    private static Thread thread;
    private static int batteryStatusSampleNumber;
    private static boolean logging;
    private static boolean logCreated = true;
    private static boolean logEntryFetched = true;
    private static boolean logListRefreshed = true;
    private static boolean gotBatteryStatus = false;

    private final IBinder binder = new LocalBinder();

    MdsLogbookEntriesResponse entriesResponse;

    // Automatic logging variables
    private Activity currentActivity;
    private String connectedMAC;
    int logId;
    private static final int RECORDING_TIME = 20;     // Minutes

    public class LocalBinder extends Binder {
        LoggerForegroundService getService() {
            return LoggerForegroundService.this;
        }

    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

        entriesResponse = null;
        batteryStatusSampleNumber = 1;

        // If the notification supports a direct reply action, use
        // PendingIntent.FLAG_MUTABLE instead.
        // This intent will enable the opening of the activity on notification tap.
        Intent notificationIntent = new Intent(this, DataLoggerActivity.class);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(this, 0, notificationIntent,
                        PendingIntent.FLAG_IMMUTABLE);

        notification =
                new Notification.Builder(this, getString(R.string.channel_data_recording_id))
                        .setContentTitle(getText(R.string.data_recording_notification_title))
                        .setContentText(getText(R.string.data_recording_notification_message))
                        .setSmallIcon(R.drawable.chart_icon)
                        .setContentIntent(pendingIntent)
                        .setTicker(getText(R.string.data_recording_notification_ticker_text))
                        .setOngoing(true)
                        .build();

        Log.d(TAG, "After notification");

        // Init Log list
//        mLogEntriesArrayAdapter = new ArrayAdapter<MdsLogbookEntriesResponse.LogEntry>(this,
//                android.R.layout.simple_list_item_1, mLogEntriesArrayList);
//        mLogEntriesListView.setAdapter(mLogEntriesArrayAdapter);


//        startForeground(ONGOING_NOTIFICATION_ID, notification);
        Log.d(TAG, "onCreate done");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(ONGOING_NOTIFICATION_ID, notification);

        automaticLoggingManagement();

        return super.onStartCommand(intent, flags, startId);
    }

    private void automaticLoggingManagement() {

        if (logId == 2)
            logCreated = logEntryFetched = logListRefreshed = true;
        else
            logCreated = logEntryFetched = logListRefreshed = false;
        gotBatteryStatus = false;

        if (GET_BATTERY_STATUS) {
            getBatteryStatusPeriodic();
        }
        else {
            gotBatteryStatus = true;
        }

        thread = new Thread() {
            @Override public void run() {
                long startTime;
                long endTime;
                while (logging) {
                    // Communication with the sensor
                    startTime = SystemClock.elapsedRealtime();
                    if (logId > 2) {
                        refreshLogList();
                        // Wait for the log list to be refreshed
//                        while (!logListRefreshed) {
//                            Log.d("BLE_CONNECTION_HANDLER", "Waiting for log list refresh...");
//                            try {
//                                thread.sleep(50);
//                            } catch (InterruptedException e) {
//                                e.printStackTrace();
//                            }
//                        }
                        fetchLogEntry(logId - 1);
                    }

                    // Wait for new log to be created and for data to be fetched before disconnecting the device
                    while (!logCreated || !logEntryFetched) { // || !gotBatteryStatus) {
                        Log.d("BLE_CONNECTION_HANDLER", "Waiting for data retrieval...");
                        try {
                            thread.sleep(100);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                    logCreated = logEntryFetched = logListRefreshed = false;
                    if (GET_BATTERY_STATUS) {
                        gotBatteryStatus = false;
                    }
                    endTime = SystemClock.elapsedRealtime();

                    try {
                        Log.d("MAC", "Connected MAC: " + connectedMAC);


                        BLEConnectionHandler.disconnectBLEDevice(connectedMAC);


                        long timeToSleep = (RECORDING_TIME * 60000 - (endTime - startTime));
                        Log.d("TIME_WAIT", "Time to sleep: " + timeToSleep);
                        if (timeToSleep < 0) {
                            thread.sleep(0);
                        }
                        else {
                            thread.sleep(timeToSleep);
                        }

                        Log.d("BLE_CONNECTION_HANDLER", "connectedMAC: " + connectedMAC);
                        Log.d("BLE_CONNECTION_HANDLER", "Connected: " + BLEConnectionHandler.getDeviceIsConnected(connectedMAC));
                        if (!BLEConnectionHandler.getDeviceIsConnected(connectedMAC))
                            BLEConnectionHandler.connectBLEDevice(connectedMAC, currentActivity);
                        while (!BLEConnectionHandler.getDeviceIsConnected(connectedMAC) && logging) {
                            Log.d("BLE_CONNECTION_HANDLER", "Waiting for connection...");
                            thread.sleep(100);
                        }


                        Log.d("BLE_CONNECTION_HANDLER", "After while. Connection completed.");
                        Log.d("TIME_WAIT", "Time after: " + System.nanoTime()/1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    // Check again for when the STOP LOGGING button is pressed
                    Log.d("BLE_CONNECTION_HANDLER", "Logging: " + logging);

                    if (logging) {
                        Log.d("BLE_CONNECTION_HANDLER", "createNewLog");
                        createNewLog();
                        logId++;
                        Log.d("BLE_CONNECTION_HANDLER", "LogId: " + logId);
                    }
                }
            }
        };
        thread.start();
    }

    private void getBatteryStatusPeriodic() {
        new Thread() {
            @Override
            public void run() {
                while (logging) {
                    getBatteryStatus();
                    try {
                        Thread.sleep(600000);   // get battery status once every 10 minutes
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }.start();
    }

    private void getBatteryStatus() {

        Log.d("BATTERY_STATUS", "\n\ngetBatteryStatus. Sample number " + batteryStatusSampleNumber);
        mDLConfigPath = null;

        for (int i=0; i<3; i++) {
            // Try with this: MovesenseConnectedDevices.getConnectedDevice(0).getSerial()
            final int idx = i;
            getMDS().get(MainActivity.SCHEME_PREFIX +
                            connectedSerial + BATTERY_PATH_GET_EXTENDED,
                    null, new MdsResponseListener() {
                        @Override
                        public void onSuccess(String s) {
                            gotBatteryStatus = true;
                            ExtendedEnergyGetModel extendedEnergyGetModel = new Gson().fromJson(s, ExtendedEnergyGetModel.class);
                            Log.d("BATTERY_STATUS", "Detection " + (idx+1));
                            Log.d("BATTERY_STATUS", "Battery percentage: " + extendedEnergyGetModel.mContent.getPercent());
                            Log.d("BATTERY_STATUS", "Battery mV: " + extendedEnergyGetModel.mContent.getMilliVoltages());
                            Log.d("BATTERY_STATUS", "Battery resistance: " + extendedEnergyGetModel.mContent.getInternalResistance());
                        }

                        @Override
                        public void onError(MdsException e) {
                            Log.e("BATTERY_STATUS", "onError: ", e);
                        }
                    });
        }
        batteryStatusSampleNumber++;
    }


    //region GETTERS/SETTERS
    public void setLogging(boolean logging) {
        this.logging = logging;
    }

    public void setLogCreated(boolean logCreated) {
        this.logCreated = logCreated;
    }

    public void setLogEntryFetched(boolean logEntryFetched) {
        this.logEntryFetched = logEntryFetched;
    }

    public void setActivity(Activity activity) {
        currentActivity = activity;
    }

    public void setConnectedMAC(String connectedMAC) {
        this.connectedMAC = connectedMAC;
    }

    public void setConnectedSerial(String connectedSerial) {
        this.connectedSerial = connectedSerial;
    }

    public void setLogId(int logId) {
        Log.d("BLE_CONNECTION_HANDLER", "setLogId: " + logId);
        this.logId = logId;
    }

    public boolean getThreadIsRunning() {
        if (thread == null)
            return false;
        else if (thread.isAlive())
            return true;
        return false;
    }

    public int getLogId() {
        return logId;
    }
    //endregion

    public MdsLogbookEntriesResponse getEntriesResponse() {
        return entriesResponse;
    }

    @Nullable
    @androidx.annotation.Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    private void fetchLogEntry(final int id) {
        Log.d("Fetch", "fetchLogEntry");

//        findViewById(R.id.headerProgress).setVisibility(View.VISIBLE);
        // GET the /MDS/Logbook/Data proxy

        String logDataUri = MessageFormat.format(URI_MDS_LOGBOOK_DATA, connectedSerial, id);
        final Context me = this;
        final long logGetStartTimestamp = new Date().getTime();

        getMDS().get(logDataUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(final String data) {
                Log.d("Fetch", "fetchLogEntry: success");
                Log.d("Fetch", "fetchLogEntry, logId: " + logId);
                logEntryFetched = true;
            }

            @Override
            public void onError(MdsException e) {
                Log.e("Fetch", "GET Log Data returned error: " + e);
//                findViewById(R.id.headerProgress).setVisibility(View.GONE);
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        if (thread != null) {
            thread.interrupt();
        }
        stopSelf();
    }

    private Mds getMDS() {return MainActivity.mMds;}

    private void createNewLog() {
        // Access the Logbook/Entries resource
        String entriesUri = MessageFormat.format(URI_LOGBOOK_ENTRIES, connectedSerial);
        Log.d("Fetch", "createNewLog");

        getMDS().post(entriesUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                logCreated = true;
                Log.i(TAG, "POST LogEntries succesful: " + data);
                Log.d("Fetch", "createNewLog: success");
                IntResponse logIdResp = new Gson().fromJson(data, IntResponse.class);
            }

            @Override
            public void onError(MdsException e) {
                Log.e(TAG, "POST LogEntries returned error: " + e);
            }
        });

    }
    //
//    private void fetchLogEntry(final int id) {
//        // GET the /MDS/Logbook/Data proxy
//        String logDataUri = MessageFormat.format(URI_MDS_LOGBOOK_DATA, connectedSerial, id);
//        final Context me = this;
//        final long logGetStartTimestamp = new Date().getTime();
//
//        getMDS().get(logDataUri, null, new MdsResponseListener() {
//            @Override
//            public void onSuccess(final String data) {
//                final long logGetEndTimestamp = new Date().getTime();
////                MdsLogbookEntriesResponse.LogEntry entry = null;
////                entry.id = logCounter;
////                MdsLogbookEntriesResponse.LogEntry e = null;
////                e = entry;
////                final float speedKBps = (float)entry.size / (logGetEndTimestamp-logGetStartTimestamp) / 1024.0f * 1000.f;
//                Log.i(TAG, "GET Log Data succesful.");
//
////                final String message = new StringBuilder()
////                        .append("Downloaded log #").append(id).append(" from the Movesense sensor.")
////                        .append("\n").append("Size:  ").append(entry.size).append(" bytes")
////                        .append("\n").append("Speed: ").append(speedKBps).append(" kB/s")
////                        .append("\n").append("\n").append("File will be saved in location you choose.")
////                        .toString();
////
////                final String filename =new StringBuilder()
////                        .append("MovesenseLog_").append(id).append(" ")
////                        .append(entry.getDateStr()).toString();
////                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(me)
////                        .setTitle("Save Log Data")
////                        .setMessage(message).setPositiveButton("Save to Phone", new DialogInterface.OnClickListener() {
////                                    @Override
////                                    public void onClick(DialogInterface dialog, int which) {
////                                        saveLogToFile(filename, data);
////                                    }
////                                }
////                        );
//
////                alertDialogBuilder.show();
//            }
//
//            @Override
//            public void onError(MdsException e) {
//                Log.e(TAG, "GET Log Data returned error: " + e);
//            }
//        });
//    }
//
////    private MdsLogbookEntriesResponse.LogEntry findLogEntry(final int id)
////    {
////        MdsLogbookEntriesResponse.LogEntry entry = null;
////        for (MdsLogbookEntriesResponse.LogEntry e : mLogEntriesArrayList) {
////            if ((e.id == id)) {
////                entry = e;
////                break;
////            }
////        }
////        return entry;
////    }
//
    private void refreshLogList() {
        // Access the /Logbook/Entries
        String entriesUri = MessageFormat.format(URI_MDS_LOGBOOK_ENTRIES, connectedSerial);
        Log.i("BLE_CONNECTION_HANDLER", "GET LogEntries");

        getMDS().get(entriesUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i("BLE_CONNECTION_HANDLER", "GET LogEntries succesful: " + data);

                entriesResponse = new Gson().fromJson(data, MdsLogbookEntriesResponse.class);
                logListRefreshed = true;


            }

            @Override
            public void onError(MdsException e) {
                Log.e(TAG, "GET LogEntries returned error: " + e);
            }
        });
    }

//    private void saveLogToFile(String filename, String data) {
//        mDataToWriteFile = data;
//        // Add extension to filename if it doesn't have yet
//        if (!filename.endsWith(".json"))
//        {
//            filename = filename + ".json";
//        }
//
//        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
//        intent.addCategory(Intent.CATEGORY_OPENABLE);
//        intent.setType("application/json");
//        intent.putExtra(Intent.EXTRA_TITLE, filename);
//
//        startActivityForResult(intent, CREATE_FILE);
//    }
}
