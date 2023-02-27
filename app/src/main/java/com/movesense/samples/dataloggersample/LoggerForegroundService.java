package com.movesense.samples.dataloggersample;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.support.annotation.Nullable;

import androidx.annotation.RequiresApi;

import android.support.v7.app.AlertDialog;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.gson.Gson;
import com.movesense.mds.Mds;
import com.movesense.mds.MdsException;
import com.movesense.mds.MdsResponseListener;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Date;

public class LoggerForegroundService extends Service {

    private static final int ONGOING_NOTIFICATION_ID = 1;
    public static final String TAG = "LoggerForegroundService";
    private static final String URI_DATALOGGER_CONFIG = "suunto://{0}/Mem/DataLogger/Config";
    private static final String URI_LOGBOOK_ENTRIES = "suunto://{0}/Mem/Logbook/Entries";
    private static final String URI_DATALOGGER_STATE = "suunto://{0}/Mem/DataLogger/State";
    private static final String URI_MDS_LOGBOOK_ENTRIES = "suunto://MDS/Logbook/{0}/Entries";
    private static final String URI_MDS_LOGBOOK_DATA= "suunto://MDS/Logbook/{0}/ById/{1}/Data";

//    private ListView mLogEntriesListView;
//    private static ArrayList<MdsLogbookEntriesResponse.LogEntry> mLogEntriesArrayList = new ArrayList<>();
//    ArrayAdapter<MdsLogbookEntriesResponse.LogEntry> mLogEntriesArrayAdapter;

    private Notification notification;
    private DataLoggerState mDLState;
    private String connectedSerial = "214630000338";
    private String mDLConfigPath;
    private Thread thread;
    private int logCounter;
    private boolean logData;

    @RequiresApi(api = Build.VERSION_CODES.O)
    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate");

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
//                        .setSmallIcon(R.drawable.chart_icon)
                        .setContentIntent(pendingIntent)
                        .setTicker(getText(R.string.data_recording_notification_ticker_text))
                        .setOngoing(true)
                        .build();

        Log.d(TAG, "After notification");
        logCounter = 2;
        logData = true;

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

        fetchDataLoggerConfig();
        refreshLogList();

        thread = new Thread() {
            @Override public void run() {
                long startTime = 0;
                long endTime = 0;
                while (logData) {
                    setDataLoggerState(true);
//                    startTime = System.nanoTime()/1000;
//                    if (logCounter > 2) {
//                        fetchLogEntry(logCounter);
//                    }
//                    endTime = System.nanoTime()/1000;
//                    try {
//                        Log.d(TAG, "Sleep time: " + (1000-(endTime-startTime)));
//                        thread.sleep(1000-(endTime-startTime));
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                    }
//                    createNewLog();
//                    logCounter++;
                }

            }
        };
        thread.start();

        return super.onStartCommand(intent, flags, startId);
    }

    @Nullable
    @androidx.annotation.Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void setDataLoggerState(final boolean bStartLogging) {
        // Access the DataLogger/State
        String stateUri = MessageFormat.format(URI_DATALOGGER_STATE, connectedSerial);
        final Context me = this;
        int newState = bStartLogging ? 3 : 2;   // 3 = logging, 2 = stop logging
        String payload = "{\"newState\":" + newState + "}";
        getMDS().put(stateUri, payload, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(TAG, "PUT DataLogger/State state succesful: " + data);

                mDLState.content = newState;
                // Update log list if we stopped
                if (!bStartLogging)
                    refreshLogList();
            }

            @Override
            public void onError(MdsException e) {
                Log.e(TAG, "PUT DataLogger/State returned error: " + e);

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

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
        logData = false;
        setDataLoggerState(false);
    }

    private Mds getMDS() {return MainActivity.mMds;}

    private void fetchDataLoggerState() {
        // Access the DataLogger/State
        String stateUri = MessageFormat.format(URI_DATALOGGER_STATE, connectedSerial);

        getMDS().get(stateUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(TAG, "GET state successful: " + data);

                mDLState = new Gson().fromJson(data, DataLoggerState.class);
            }

            @Override
            public void onError(MdsException e) {
                Log.e(TAG, "GET DataLogger/State returned error: " + e);
            }
        });
    }

    private boolean mPathSelectionSetInternally = false;
    private void fetchDataLoggerConfig() {
        // Access the DataLogger/State
        String stateUri = MessageFormat.format(URI_DATALOGGER_CONFIG, connectedSerial);
        mDLConfigPath = null;

        getMDS().get(stateUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(TAG, "GET DataLogger/Config succesful: " + data);

                DataLoggerConfig config = new Gson().fromJson(data, DataLoggerConfig.class);
//                Spinner spinner = (Spinner)findViewById(R.id.path_spinner);
                for (DataLoggerConfig.DataEntry de : config.content.dataEntries.dataEntry) {
                    Log.d(TAG, "DataEntry: " + de.path);

                    String dePath = de.path;
                    if (dePath.contains("{")) {
                        dePath = dePath.substring(0, dePath.indexOf('{'));
                        Log.d(TAG, "dePath: " + dePath);

                    }
                    // Start searching for item from 1 since 0 is the default text for empty selection
//                    for (int i=1; i<spinner.getAdapter().getCount(); i++)
//                    {
//                        String path = spinner.getItemAtPosition(i).toString();
//                        Log.d(TAG, "spinner.path["+ i+"]: " + path);
//                        // Match the beginning (skip the part with samplerate parameter)
//                        if (path.toLowerCase().startsWith(dePath.toLowerCase()))
//                        {
//                            mPathSelectionSetInternally = true;
//                            Log.d(TAG, "mPathSelectionSetInternally to #"+ i);
//
////                            spinner.setSelection(i);
                    mDLConfigPath = "/Meas/Acc/13";
//                            break;
//                        }
//                    }
                }
                // If no match found, set to first item (/Meas/Acc/13)
//                if (mDLConfigPath == null)
//                {
//                    Log.d(TAG, "no match found, set to first item");
//
//                    spinner.setSelection(0);
//                }

                fetchDataLoggerState();
            }

            @Override
            public void onError(MdsException e) {
                Log.e(TAG, "GET DataLogger/Config returned error: " + e);
                fetchDataLoggerState();
            }
        });
    }

    private void createNewLog() {
        // Access the Logbook/Entries resource
        String entriesUri = MessageFormat.format(URI_LOGBOOK_ENTRIES, connectedSerial);

        getMDS().post(entriesUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(TAG, "POST LogEntries succesful: " + data);
                IntResponse logIdResp = new Gson().fromJson(data, IntResponse.class);
            }

            @Override
            public void onError(MdsException e) {
                Log.e(TAG, "POST LogEntries returned error: " + e);
            }
        });

    }

    private void fetchLogEntry(final int id) {
        // GET the /MDS/Logbook/Data proxy
        String logDataUri = MessageFormat.format(URI_MDS_LOGBOOK_DATA, connectedSerial, id);
        final Context me = this;
        final long logGetStartTimestamp = new Date().getTime();

        getMDS().get(logDataUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(final String data) {
                final long logGetEndTimestamp = new Date().getTime();
//                MdsLogbookEntriesResponse.LogEntry entry = null;
//                entry.id = logCounter;
//                MdsLogbookEntriesResponse.LogEntry e = null;
//                e = entry;
//                final float speedKBps = (float)entry.size / (logGetEndTimestamp-logGetStartTimestamp) / 1024.0f * 1000.f;
                Log.i(TAG, "GET Log Data succesful.");

//                final String message = new StringBuilder()
//                        .append("Downloaded log #").append(id).append(" from the Movesense sensor.")
//                        .append("\n").append("Size:  ").append(entry.size).append(" bytes")
//                        .append("\n").append("Speed: ").append(speedKBps).append(" kB/s")
//                        .append("\n").append("\n").append("File will be saved in location you choose.")
//                        .toString();
//
//                final String filename =new StringBuilder()
//                        .append("MovesenseLog_").append(id).append(" ")
//                        .append(entry.getDateStr()).toString();
//                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(me)
//                        .setTitle("Save Log Data")
//                        .setMessage(message).setPositiveButton("Save to Phone", new DialogInterface.OnClickListener() {
//                                    @Override
//                                    public void onClick(DialogInterface dialog, int which) {
//                                        saveLogToFile(filename, data);
//                                    }
//                                }
//                        );

//                alertDialogBuilder.show();
            }

            @Override
            public void onError(MdsException e) {
                Log.e(TAG, "GET Log Data returned error: " + e);
            }
        });
    }

//    private MdsLogbookEntriesResponse.LogEntry findLogEntry(final int id)
//    {
//        MdsLogbookEntriesResponse.LogEntry entry = null;
//        for (MdsLogbookEntriesResponse.LogEntry e : mLogEntriesArrayList) {
//            if ((e.id == id)) {
//                entry = e;
//                break;
//            }
//        }
//        return entry;
//    }

    private void refreshLogList() {
        // Access the /Logbook/Entries
        String entriesUri = MessageFormat.format(URI_MDS_LOGBOOK_ENTRIES, connectedSerial);

        getMDS().get(entriesUri, null, new MdsResponseListener() {
            @Override
            public void onSuccess(String data) {
                Log.i(TAG, "GET LogEntries succesful: " + data);

                MdsLogbookEntriesResponse entriesResponse = new Gson().fromJson(data, MdsLogbookEntriesResponse.class);

//                mLogEntriesArrayList.clear();
//                for (MdsLogbookEntriesResponse.LogEntry logEntry : entriesResponse.logEntries) {
//                    Log.d(TAG, "Entry: " + logEntry);
//                    mLogEntriesArrayList.add(logEntry);
//                }
//                mLogEntriesArrayAdapter.notifyDataSetChanged();
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
