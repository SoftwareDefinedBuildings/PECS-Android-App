package com.michaelchen.chairtalk;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.speech.RecognizerIntent;
import android.support.v7.app.ActionBarActivity;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;


public class MainActivity extends ActionBarActivity {

    public static final boolean MASTER_CHAIR_CONTROL = false;

    private static boolean setRefreshAlarm = false;
    private static boolean setSyncAlarm = false;

    public static final String TAG = "MainActivity";
    /*
    FOR SEPARATE SLIDERS
    private SeekBar seekBottomFan;
    private SeekBar seekBackFan;
    private SeekBar seekBottomHeat;
    private SeekBar seekBackHeat;
    */
    // FOR COMBINED SLIDERS
    private SeekBar seekBack;
    private SeekBar seekBottom;
    public static final String uri = "http://54.215.11.207:38001"; //"http://169.229.137.160:38001";
    private static final String QUERY_STRING = "http://shell.storm.pm:8079/api/query";
    public static final int refreshPeriod = 15000;
    public static final int syncRefreshPeriod = 60000;
    public static final int DISCONNECTED_BL_PERIOD = 5100;
    public static final int CONNECTED_BL_PERIOD = 12000;
    public static int blCheckPeriod = DISCONNECTED_BL_PERIOD;
    public static int prevBLCheckPeriod = 0; // something different so it fires the first time
    //public static final int smapDelay = 20000;
    private static int blCheck = 1;
    private static int blExpect = 0;
    private static BluetoothManager bluetoothManager = null;
    private Date lastUpdate; //TODO: check to make sure smap loop never happens
    public static boolean verifiedConnection = true;
    public static boolean manuallyDisconnected = false;

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";
    protected static final int REQUEST_OK = 1;
    protected static final int BLUETOOTH_REQUEST = 33;

    public static int outstanding_requests = 0;

    private boolean waitingForBLOn = false;

    public static boolean inMainApp = false;

    static final String BACK_FAN = "Back Fan";
    static final String BOTTOM_FAN = "Bottom Fan";
    static final String BACK_HEAT = "Back Heat";
    static final String BOTTOM_HEAT = "Bottom Heat";
    static final String LAST_TIME = "Last Time";
    static final String WF_KEY = "wifi_mac";
    static final String FIRST_LAUNCH = "first_launch";
    static final String SKIP_BL = "skip_bluetooth";

    static final Map<String, String> uuidToKey;
    static final Map<String, String> keyToUuid;
    static final Map<String, String> jsonToKey;

    /*
    I got some of this code from here: http://stackoverflow.com/questions/9693755/detecting-state-changes-made-to-the-bluetoothadapter
     */
    private final BroadcastReceiver bluetoothChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                switch (state) {
                    case BluetoothAdapter.STATE_OFF:
                        BluetoothAdapter.getDefaultAdapter().enable();
                        break;
                    case BluetoothAdapter.STATE_ON:
                        if (MainActivity.this.waitingForBLOn) {
                            MainActivity.this.waitingForBLOn = false;
                            MainActivity.this.findChair();
                        }
                        break;
                }
            }
        }
    };

    /*  Definitely not the best way of doing things, but it lets any BluetoothActivity
        find the current MainActivity so it can clear the Node ID when needed.
     */
    static MainActivity currActivity;

    static {
        Map<String, String> temp = new HashMap<>();
        temp.put("27e1e889-b749-5cf9-8f90-5cc5f1750ddf", BACK_FAN);
        temp.put("33ecc20c-e636-58eb-863f-142717105075", BACK_HEAT);
        temp.put("a99daf41-f3b3-51a7-97bf-48fb3e7bf130", BOTTOM_HEAT);
        temp.put("b7ef2e98-2e0a-515b-b534-69894fdddf6f", BOTTOM_FAN);
        uuidToKey = Collections.unmodifiableMap(temp);
        temp = new HashMap<>();
        for(Map.Entry<String, String> entry : uuidToKey.entrySet()){
            temp.put(entry.getValue(), entry.getKey());
        }
        keyToUuid = Collections.unmodifiableMap(temp);
        temp = new HashMap<>();
        temp.put("backf", BACK_FAN);
        temp.put("bottomf", BOTTOM_FAN);
        temp.put("backh", BACK_HEAT);
        temp.put("bottomh", BOTTOM_HEAT);
        temp.put("time", LAST_TIME);
        jsonToKey = Collections.unmodifiableMap(temp);
    }

    public boolean synchronized_with_server = false;
    private double curr_offset = 0;
    private static final double ALPHA = 0.2;

    public void registerTimeSync(double computed_offset) {
        if (synchronized_with_server) {
            curr_offset = curr_offset + ALPHA * (computed_offset - curr_offset);
        } else {
            curr_offset = computed_offset;
            synchronized_with_server = true;
        }
    }

    public void synchronizeTimeAsync() {
        TimeSynchronizerAsyncTask tsat = new TimeSynchronizerAsyncTask();
        tsat.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, uri);
    }

    private void setVerifiedConnection(boolean connected) {
        if (connected ^ verifiedConnection) {
            TextView t = (TextView) findViewById(R.id.status);
            if (connected) {
                blCheckPeriod = CONNECTED_BL_PERIOD;
                t.setText("Status: Connected to chair.");
            } else {
                blCheckPeriod = DISCONNECTED_BL_PERIOD;
                if (!manuallyDisconnected) {
                    t.setText("Status: Chair is not responding to bluetooth...");
                }
            }
            rescheduleBLTimer(blCheckPeriod + 500);
        }
        verifiedConnection = connected;
    }

    public double get_time() {
        double base = System.currentTimeMillis() / 1000.0;
        double modified = curr_offset + base;
        return modified;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        SharedPreferences sharedPref = this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        if (sharedPref.getBoolean(FIRST_LAUNCH, true)) {
            Intent i = new Intent(this, Tutorial.class);
            startActivity(i);
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        initSeekbarListeners();
        setSeekbarPositions();
        updateLastUpdate();
        lastUpdate = new Date();
        if (!setRefreshAlarm) {
            setRecurringAlarm(refreshPeriod, 0);
            setRefreshAlarm = true;
        }
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
        String name = sp.getString(SettingsActivity.NAME, "");
        TextView tv = (TextView) findViewById(R.id.textViewVoice);
        tv.setText(getString(R.string.hello) + " " + name);

        //rescheduleTimer(0);
        //rescheduleSyncTimer(0);
        if (!setSyncAlarm) {
            setRecurringSyncAlarm(syncRefreshPeriod, 0);
            setSyncAlarm = true;
        }
        rescheduleBLTimer(0);

        MainActivity.currActivity = this;

        IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        registerReceiver(bluetoothChangeReceiver, filter);
    }

    private void initBle() {
        final Intent intent = getIntent();
        if (intent != null && intent.hasExtra(EXTRAS_DEVICE_NAME) && intent.hasExtra(EXTRAS_DEVICE_ADDRESS)) {
//            String mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
            String mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);
            if (bluetoothManager == null || !bluetoothManager.isConnected()) {
                bluetoothManager = new BluetoothManager(this, mDeviceAddress);
            }
            TextView t = (TextView) findViewById(R.id.chair_desc);
            t.setText(getString(R.string.chair_desc) + mDeviceAddress);

            t = (TextView) currActivity.findViewById(R.id.status);
            setVerifiedConnection(true);
            if (manuallyDisconnected) {
                t.setText("Status: Manually disconnected from chair.");
            } else {
                t.setText("Status: Initiated bluetooth connection. Waiting for chair...");
            }
        } else {
            SharedPreferences sharedPreferences = getSharedPreferences(getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
            String mac = sharedPreferences.getString(BluetoothManager.MAC_KEY, "");
            if (!mac.equals("")) {
                Intent i = new Intent(this, BluetoothActivity.class);
                startActivity(i);
            }
        }
    }

    @Override
    protected void onResume() {
        MainActivity.currActivity = this;

        super.onResume();
        Intent i = getIntent();
        if (i != null && i.getBooleanExtra(SKIP_BL, false)) {

        } else if (bluetoothManager == null || !bluetoothManager.isConnected()) {
            initBle();
        }


        final Timer startupUpdater = new Timer();

        TimerTask tt = new TimerTask() {
            public void run() {
                sendUpdateBle(true);
                System.out.println("Sending update");
                startupUpdater.cancel();
                startupUpdater.purge();
            }
        };

        startupUpdater.schedule(tt, 1500);

        //rescheduleTimer(0);
        //rescheduleSyncTimer(0);
        //rescheduleBLTimer(0);
        //if (bluetoothManager != null) bluetoothManager.onResume();
    }

    @Override
    protected void onPause() {
        super.onPause();
        //cancelTimer();
        //cancelSyncTimer();
        //cancelBLTimer(); // Don't ping bluetooth connection when paused
        //if (bluetoothManager != null) bluetoothManager.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        /*cancelTimer();
        cancelSyncTimer();
        cancelBLTimer();
        if (bluetoothManager != null) bluetoothManager.onDestroy();*/
    }

    /* FOR FOUR SLIDERS, ONE FOR EACH SEPARATE CONTROL
    protected void initSeekbarListeners() {
        seekBackFan = (SeekBar) findViewById(R.id.seekBarBackFan);
        seekBackFan.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int currentPosition = 0;

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser){
                currentPosition = progress;
            }

            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                MainActivity.this.updatePref(BACK_FAN, currentPosition);
                sendUpdateLocal();
            }
        });

        seekBottomFan = (SeekBar) findViewById(R.id.seekBarBottomFan);
        seekBottomFan.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int currentPosition = 0;

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser){
                currentPosition = progress;
            }

            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                MainActivity.this.updatePref(BOTTOM_FAN, currentPosition);
                sendUpdateLocal();
            }
        });

        seekBackHeat = (SeekBar) findViewById(R.id.seekBarBackHeat);
        seekBackHeat.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int currentPosition = 0;

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentPosition = progress;
            }

            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                MainActivity.this.updatePref(BACK_HEAT, currentPosition);
                sendUpdateLocal();
            }
        });

        seekBottomHeat = (SeekBar) findViewById(R.id.seekBarBottomHeat);
        seekBottomHeat.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            int currentPosition = 0;

            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                currentPosition = progress;
            }

            public void onStartTrackingTouch(SeekBar seekBar) {

            }

            public void onStopTrackingTouch(SeekBar seekBar) {
                MainActivity.this.updatePref(BOTTOM_HEAT, currentPosition);
                sendUpdateLocal();
            }
        });
    }*/

    // FOR TWO SLIDERS
    class ChairSeekbarListener implements SeekBar.OnSeekBarChangeListener {
        private int currentPosition = 0;
        private String heater, fan;

        public ChairSeekbarListener(String heater, String fan) {
            this.heater = heater;
            this.fan = fan;
        }

        public void onProgressChanged(SeekBar seekbar, int progress, boolean fromUser) {
            currentPosition = progress;
        }

        public void onStartTrackingTouch(SeekBar seekBar) {
        }

        public void onStopTrackingTouch(SeekBar seekBar) {
            int heatSetting, fanSetting;
            if (currentPosition > 100) {
                heatSetting = 0;
                fanSetting = currentPosition - 100;
            } else if (currentPosition < 100) {
                heatSetting = 100 - currentPosition;
                fanSetting = 0;
            } else {
                heatSetting = 0;
                fanSetting = 0;
            }

            MainActivity.this.updatePref(this.fan, fanSetting);
            MainActivity.this.updatePref(this.heater, heatSetting);
            sendUpdateLocal();
        }
    }

    class ChairOffListener implements Button.OnClickListener {
        private String heater, fan;
        public ChairOffListener(String heater, String fan) {
            this.heater = heater;
            this.fan = fan;
        }
        public void onClick(View button) {
            MainActivity.this.updatePref(this.fan, 0);
            MainActivity.this.updatePref(this.heater, 0);
            MainActivity.this.setSeekbarPositions();
            sendUpdateLocal();
        }
    }

    protected void initSeekbarListeners() {
        seekBack = (SeekBar) findViewById(R.id.seekBarBack);
        seekBack.setOnSeekBarChangeListener(new ChairSeekbarListener(BACK_HEAT, BACK_FAN));

        seekBottom = (SeekBar) findViewById(R.id.seekBarBottom);
        seekBottom.setOnSeekBarChangeListener(new ChairSeekbarListener(BOTTOM_HEAT, BOTTOM_FAN));

        Button backOff = (Button) findViewById(R.id.backOff);
        backOff.setOnClickListener(new ChairOffListener(BACK_HEAT, BACK_FAN));

        Button bottomOff = (Button) findViewById(R.id.bottomOff);
        bottomOff.setOnClickListener(new ChairOffListener(BOTTOM_HEAT, BOTTOM_FAN));
    }

    /*private void repairBL() {
        if (bluetoothManager != null) {
            System.out.println("Repairing");
            bluetoothManager.disconnect();
            bluetoothManager = null;
            //initBle();
        }
    }*/

    /*
        FOR FOUR SLIDERS, SEPARATE FOR EACH CONTROL
    protected void setSeekbarPositions() {
        SharedPreferences sharedPref = this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);

        int backFanPos = sharedPref.getInt(BACK_FAN, 0);
        seekBackFan.setProgress(backFanPos);
        int bottomFanPos = sharedPref.getInt(BOTTOM_FAN, 0);
        seekBottomFan.setProgress(bottomFanPos);

        int backHeatPos = sharedPref.getInt(BACK_HEAT, 0);
        seekBackHeat.setProgress(backHeatPos);
        int bottomHeatPos = sharedPref.getInt(BOTTOM_HEAT, 0);
        seekBottomHeat.setProgress(bottomHeatPos); 
    }*/

    // FOR TWO SLIDERS
    protected void setSeekbarPositions() {
        SharedPreferences sharedPref = this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);

        int backFanPos = sharedPref.getInt(BACK_FAN, 0);
        int bottomFanPos = sharedPref.getInt(BOTTOM_FAN, 0);
        int backHeatPos = sharedPref.getInt(BACK_HEAT, 0);
        int bottomHeatPos = sharedPref.getInt(BOTTOM_HEAT, 0);
        if ((backFanPos * backHeatPos) != 0 || (bottomFanPos * bottomHeatPos) != 0) {
            System.out.println("WARNING: found settings more general than UI can display");
        }

        if (bottomFanPos == 0) {
            seekBottom.setProgress(100 - bottomHeatPos);
        } else {
            seekBottom.setProgress(100 + bottomFanPos);
        }

        if (backFanPos == 0) {
            seekBack.setProgress(100 - backHeatPos);
        } else {
            seekBack.setProgress(100 + backFanPos);
        }
    }

    protected void updateLastPullTime() {
        Date time = new Date();
        TextView t = (TextView) findViewById(R.id.textViewlastPullTime);
        DateFormat df = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
        t.setText("Last Update (Pull): " + df.format(time));
    }


    protected void updateLastUpdate() {
        SharedPreferences sharedPref = this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        double lastUpdateTime = Double.longBitsToDouble(sharedPref.getLong(getString(R.string.last_server_push_key), 0));
        if (lastUpdateTime != -1) {
            Date time = new Date((long) (1000 * lastUpdateTime + 0.5));
            TextView t = (TextView) findViewById(R.id.textViewlastUpdateTime);
            DateFormat df = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss");
            t.setText("Last Update (Push): " + df.format(time));
        }
    }

    protected boolean updatePref(String key, int value) {
        // update heating or cooling
        SharedPreferences sharedPref = MainActivity.this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sharedPref.edit();
        e.putInt(key, value);
        e.apply();
        return e.commit();
    }

    protected boolean updatePref(String key, double value) {
        // update heating or cooling
        SharedPreferences sharedPref = MainActivity.this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sharedPref.edit();
        e.putLong(key, Double.doubleToRawLongBits(value));
        e.apply();
        return e.commit();
    }

    protected void sendUpdateSmap(boolean fromFS) {
        SharedPreferences sharedPref = MainActivity.this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        int backFanPos = sharedPref.getInt(BACK_FAN, 0);
        int bottomFanPos = sharedPref.getInt(BOTTOM_FAN, 0);
        int backHeatPos = sharedPref.getInt(BACK_HEAT, 0);
        int bottomHeatPos = sharedPref.getInt(BOTTOM_HEAT, 0);
        boolean inChair = sharedPref.getBoolean(getString(R.string.in_chair_key), false);
        int temp = sharedPref.getInt(getString(R.string.temp_key), 0);
        int humidity = sharedPref.getInt(getString(R.string.humidity_key), 0);
        JSONObject jsonobj = createJsonObject(backFanPos, bottomFanPos, backHeatPos, bottomHeatPos, inChair, temp, humidity, 0, fromFS);
        HttpAsyncTask task = new HttpAsyncTask(jsonobj);
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, uri);
    }

    protected void updateJsonText(JSONObject jsonobj) {
        TextView t = (TextView) findViewById(R.id.textViewJson);
        t.setText(jsonobj.toString());
    }

    /**
     * Called when a change has just been made to the settings, and we don't want to pull data from the server
     * immediately, since that would overwrite the changes that were made.
     * This used to just call rescheduleTimer(smapDelay) to wait for the changes to propagate in the server first.
     * Now, we use a better technique. I log the (synchronized) time and check when pulling data from the server
     * whether it is at least as recent.
     */
    private void rescheduleTimer() {
        // rescheduleTimer(smapDelay); // WHAT THIS FUNCTION USED TO DO
        MainActivity.this.updatePref(getString(R.string.last_server_push_key), get_time());
    }

    static int strikes = 0;
    public static class BLCheckReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            System.out.println("RECEIVED INTENT");
            if (!inMainApp || currActivity == null) {
                return;
            }
            currActivity.setVerifiedConnection(blCheck != blExpect);
            System.out.println("Setting verified connection " + blCheck + " " + blExpect);
            if (verifiedConnection) {
                strikes = 0;
            } else {
                if (manuallyDisconnected) {
                    strikes = 0;
                } else if (++strikes == 3) {
                    strikes = 0;
                    // Disconnect from the chair
                    currActivity.disconnect();
                    // Restart bluetooth
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    TextView t = (TextView) currActivity.findViewById(R.id.status);
                    t.setText("Status: Restarting bluetooth and attempting to reconnect...");
                    if (adapter.isEnabled()) {
                        adapter.disable();
                    } else {
                        adapter.enable();
                    }

                    // Try to reconnect with the chair
                    currActivity.waitingForBLOn = true;
                    /* We used to use this code, but now the bluetoothChangeReceiver takes care of it
                    currActivity.findChair();
                    */
                }
            }
            blCheck = blExpect;

            byte[] bytes = new byte[5];
            bytes[0] = (byte) (blCheck >>> 24);
            bytes[1] = (byte) ((blCheck >>> 16) & 0xFF);
            bytes[2] = (byte) ((blCheck >>> 8) & 0xFF);
            bytes[3] = (byte) ((blCheck & 0xFF));
            bytes[4] = 3;
            System.out.println("SENDING BLUETOOTH CHECK");
            currActivity.writeBleByteArray(bytes);
        }
    }

    void rescheduleBLTimer(int delay) {
        if (blCheckPeriod != prevBLCheckPeriod) {
            prevBLCheckPeriod = blCheckPeriod;
            setRecurringBLAlarm(blCheckPeriod, delay);
        }
    }

    public void sendUpdateLocal() {
        // used to update everything if user changes value from app
        rescheduleTimer();
        sendUpdateBle(true);
        outstanding_requests++;
        sendUpdateSmap(false);
        lastUpdate = new Date();
    }

    public void clearNodeID() {
        SharedPreferences sharedPref = MainActivity.this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sharedPref.edit();
        e.putString(WF_KEY, "");
        e.apply();
        e.commit();
    }

    // NO LONGER NEEDED, since we use server-side timestamps instead of delays
    /*private boolean validUpdateTime() {
        Date currentTime = new Date();
        long seconds = (currentTime.getTime()-lastUpdate.getTime());
        return seconds > smapDelay;
    }*/

    void setBleStatus(byte[] status) {
        System.out.println("Got message of length " + status.length);
        System.out.print("Got ");
        for (int i = 0; i < status.length; i++) {
            System.out.print(status[i]);
            System.out.print(" ");
        }
        System.out.println();

        if (status.length < 5) {
            return;
        }

        if (status.length == 5) {
            if (status[4] == 3) {
                int receivedEcho = (unsignedByteToInt(status[0]) << 24)
                        + (unsignedByteToInt(status[1]) << 16)
                        + (unsignedByteToInt(status[2]) << 8)
                        + unsignedByteToInt(status[3]);
                if (receivedEcho == blExpect) {
                    System.out.println("Got expected acknowledgement");
                    setVerifiedConnection(true);
                    // We got the acknowledgement we were looking for
                    blExpect++;
                }
            }
            return;
        }

        if (status.length == 11 || status.length == 19) {
            // Valid protocol, assume it's coming from a chair
            setVerifiedConnection(true);
            blExpect = blCheck + 1;
        } else {
            return; // invalid protocol, may crash app if I parse this
        }

        int temp = ((unsignedByteToInt(status[5])) << 8) + unsignedByteToInt(status[6]);
        int humidity = ((unsignedByteToInt(status[7])) << 8) + unsignedByteToInt(status[8]);
        String nodeid = null;
        if (status.length >= 11) {
            nodeid = Integer.toHexString((unsignedByteToInt(status[9]) << 8) + unsignedByteToInt(status[10]));
        }
        SharedPreferences sharedPref;
        SharedPreferences.Editor e;
        if (status.length == 19) {
            sharedPref = MainActivity.this.getSharedPreferences(
                    getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
            e = sharedPref.edit();
            e.putString(WF_KEY, nodeid);
            e.apply();
            e.commit();
            // This contains historical data. So just post to the server, and don't update preferences.
            int timestamp = (unsignedByteToInt(status[11]) << 24) + (unsignedByteToInt(status[12]) << 16) + (unsignedByteToInt(status[13]) << 8) + (unsignedByteToInt(status[14]));
            int ack_id = (unsignedByteToInt(status[15]) << 24) + (unsignedByteToInt(status[16]) << 16) + (unsignedByteToInt(status[17]) << 8) + (unsignedByteToInt(status[18]));
            JSONObject jsonobj = createJsonObject(status[2], status[3], status[0], status[1], status[4] != 0, temp, humidity, timestamp, true);
            HttpAsyncTask task = new HttpAsyncTask(jsonobj, ack_id);
            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, uri);
            return;
        }

        // If it's not historical, relay the data to the server for synchronization (15.4 may not be present)

        /*if (!validUpdateTime()) {
            return;
        }*/

        sharedPref = MainActivity.this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        e = sharedPref.edit();
        e.putInt(BACK_HEAT, status[0]);
        e.putInt(BOTTOM_HEAT, status[1]);
        e.putInt(BACK_FAN, status[2]);
        e.putInt(BOTTOM_FAN, status[3]);
        e.putBoolean(getString(R.string.in_chair_key), (status[4] != 0));
        e.putInt(getString(R.string.temp_key), temp);
        e.putInt(getString(R.string.humidity_key), humidity);
        e.apply();
        e.commit();
        rescheduleTimer();
        sendUpdateSmap(true);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                MainActivity.this.setSeekbarPositions();
            }
        });
    }

    public static int unsignedByteToInt(byte b) {
        return b & 0xff;
    }

    private byte[] getByteStatus() {
        SharedPreferences sharedPref = MainActivity.this.getSharedPreferences(
                getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        int backFanPos = sharedPref.getInt(BACK_FAN, 0);
        int bottomFanPos = sharedPref.getInt(BOTTOM_FAN, 0);
        int backHeatPos = sharedPref.getInt(BACK_HEAT, 0);
        int bottomHeatPos = sharedPref.getInt(BOTTOM_HEAT, 0);
        byte[] ret = {(byte) backHeatPos, (byte) bottomHeatPos, (byte) backFanPos, (byte) bottomFanPos, 1};
        return ret;
    }


    public void sendUpdateBle(boolean notifyUser) {
        if (notifyUser && !verifiedConnection) {
            if (manuallyDisconnected) {
                Toast.makeText(currActivity.getApplicationContext(), getString(R.string.no_bl_manual), Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(currActivity.getApplicationContext(), getString(R.string.no_bl), Toast.LENGTH_SHORT).show();
            }
        }
        boolean result = writeBleByteArray(getByteStatus());
        if (!result) {
            System.err.println("Bluetooth update appears to fail.");
        }
    }

    private boolean writeBleByteArray(byte[] data) {
        if (bluetoothManager != null && bluetoothManager.writeData(data)) {
            return true;
        }
        return false;
    }

    /*void disconnectBluetoothManager() {
        bluetoothManager = null;
        recreate();
    }*/

    private JSONObject createJsonObject(int backFan, int bottomFan, int backHeat, int bottomHeat, boolean inChair,
                                        int temp, int humidity, int timestamp, boolean fromFS) {
        JSONObject jsonobj = new JSONObject();
        JSONObject header = new JSONObject();
        try {
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
            String name = sp.getString(SettingsActivity.NAME, "Bob the Builder");
            header.put("devicemodel", android.os.Build.MODEL); // Device model
            header.put("deviceVersion", android.os.Build.VERSION.RELEASE); // Device OS version
            header.put("language", Locale.getDefault().getISO3Language()); // Language
            header.put("name", name);
            jsonobj.put("header", header);

            jsonobj.put("occupancy", inChair);
            jsonobj.put("backf", backFan);
            jsonobj.put("bottomf", bottomFan);
            jsonobj.put("backh", backHeat);
            jsonobj.put("bottomh", bottomHeat);
            jsonobj.put("temperature", temp);
            jsonobj.put("humidity", humidity);
            jsonobj.put("fromFS", fromFS);
            if (timestamp != 0) {
                jsonobj.put("timestamp", timestamp);
            }
            SharedPreferences sharedPreferences = getSharedPreferences(getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
            String wfmac = sharedPreferences.getString(WF_KEY, "");
            jsonobj.put("macaddr", wfmac);
        } catch (JSONException e) {

        }
        return jsonobj;
    }

    private class HttpAsyncTask extends AsyncTask<String, Void, Boolean> {
        private JSONObject jsonobj;
        private int bluetooth_ack;
        protected String uri_dest;
        protected boolean request_handler;
        public HttpAsyncTask(JSONObject jsonobj) {
            super();
            this.uri_dest = uri;
            this.jsonobj = jsonobj;
            this.bluetooth_ack = -1; // sMAP update
            this.request_handler = true;
        }
        public HttpAsyncTask(JSONObject jsonobj, int bluetooth_ack) {
            this(jsonobj);
            this.bluetooth_ack = bluetooth_ack; // Historical data update
            System.out.println("Sending historical point!");
            this.request_handler = false;
        }
        protected String makeRequest() throws IOException {
            System.out.println("Sending request to " + this.uri_dest);
            DefaultHttpClient httpclient = new DefaultHttpClient();
            HttpPost httpPostReq = new HttpPost(this.uri_dest);
            StringEntity se = new StringEntity(jsonobj.toString());

            httpPostReq.setEntity(se);
            httpPostReq.setHeader("Accept", "application/json");
            httpPostReq.setHeader("Content-type", "application/json");
            HttpResponse httpResponse = httpclient.execute(httpPostReq);
            InputStream inputStream = httpResponse.getEntity().getContent();
            return inputStreamToString(inputStream);
        }
        @Override
        protected Boolean doInBackground(String...urls) {
            try {
                final String response = makeRequest();
                Log.d("httpPost", response);
                System.out.println("Response: " + response);
                if (response.equals("")) {
                    return false;
                }
                if (this.bluetooth_ack == -1) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // Toast.makeText(getBaseContext(), "Post Result: " + response, Toast.LENGTH_SHORT).show();
                            double transactionTime = Double.parseDouble(response);
                            MainActivity.this.updatePref(getString(R.string.last_server_push_key), transactionTime);
                            MainActivity.this.updatePref(LAST_TIME, transactionTime);
                            MainActivity.this.updateLastUpdate();
                            MainActivity.this.updateJsonText(jsonobj);
                        }
                    });
                } else if (response.equals("success")) {
                    // Send bluetooth ack to chair
                    System.out.println("Sending bluetooth ack");
                    byte[] ack_array = new byte[5];
                    ack_array[0] = (byte) (this.bluetooth_ack >> 24);
                    ack_array[1] = (byte) (this.bluetooth_ack >> 16);
                    ack_array[2] = (byte) (this.bluetooth_ack >> 8);
                    ack_array[3] = (byte) this.bluetooth_ack;
                    ack_array[4] = 2; // to indicate that this is an acknowledgement
                    final boolean result = writeBleByteArray(ack_array);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            TextView t = (TextView) findViewById(R.id.textViewVoice);
                            if (result) {
                                t.setText("Successfully sent acknowledgement " + bluetooth_ack);
                            } else {
                                t.setText("Could not sent acknowledgement " + bluetooth_ack);
                            }
                        }
                    });
                }

                return true;
            } catch (IOException e) {
                Log.d("httpPost", "failed");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                    Toast.makeText(getBaseContext(), "Check Internet Connection", Toast.LENGTH_SHORT).show();
                    }
                });
                return false;
            } finally {
                if (this.request_handler) {
                    outstanding_requests--;
                    if (outstanding_requests < 0) {
                        outstanding_requests = 0;
                    }
                }
            }
        }
        // onPostExecute displays the results of the AsyncTask.
        protected void onPostExecute(String result) {
        }
    }

    private class TimeSynchronizerAsyncTask extends HttpAsyncTask {
        public TimeSynchronizerAsyncTask() {
            super(new JSONObject());
            this.request_handler = false;
        }

        @Override
        protected Boolean doInBackground(String... urls) {
            double start, end, server, computed_offset;
            try {
                start = System.currentTimeMillis() / 1000.0;
                final String response = makeRequest();
                end = System.currentTimeMillis() / 1000.0;
                server = Double.parseDouble(response);
                computed_offset = server - ((end + start) / 2);
                registerTimeSync(computed_offset);
                System.out.println("Successfully synchronized time.");
                return true;
            } catch (NumberFormatException nfe) {
                System.out.println("Time synchronization attempt FAILS.");
            } catch (IOException ioe) {
                Log.d("httpPost", "failed");
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        Toast.makeText(getBaseContext(), "Check Internet Connection", Toast.LENGTH_SHORT).show();
                    }
                });
            }
            return false;
        }
    }

    static String inputStreamToString(InputStream inputStream) throws IOException {
        BufferedReader bufferedReader = new BufferedReader( new InputStreamReader(inputStream));
        String line = "";
        String result = "";
        while((line = bufferedReader.readLine()) != null)
            result += line;

        inputStream.close();
        return result;

    }

    //@Deprecated, but allows direct query of smap should server fail
    private class SmapQueryAsyncTask extends AsyncTask<String, Void, Boolean> {
        private String uuid;
        public static final String QUERY_LINE = "select data before now where uuid = '%s'";
        public SmapQueryAsyncTask(String uuid) {
            super();
            this.uuid = uuid;
        }
        @Override
        protected Boolean doInBackground(String...urls) {
            for(String url: urls) {
                final String uri = url;
                Thread t = new Thread(new Runnable() {
                    @Override
                    public void run() {
                    try {
                        DefaultHttpClient httpclient = new DefaultHttpClient();
                        HttpPost httpPostReq = new HttpPost(uri);
                        StringEntity se = new StringEntity(String.format(QUERY_LINE, uuid));

                        httpPostReq.setEntity(se);
                        HttpResponse httpResponse = httpclient.execute(httpPostReq);
                        InputStream inputStream = httpResponse.getEntity().getContent();
                        final String response = inputStreamToString(inputStream);
                        Log.d("httpPost", response);
                        JSONObject jsonResponse = new JSONObject(response.substring(1, response.length() - 1));
                        JSONArray readings = ((JSONArray) jsonResponse.getJSONArray("Readings")).getJSONArray(0);
                        String retUuid = jsonResponse.getString("uuid");
                        int value = readings.getInt(1);
                        long time = readings.getLong(0);
                        MainActivity.this.updatePref(MainActivity.this.uuidToKey.get(retUuid), value);
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                            MainActivity.this.setSeekbarPositions();
                            }
                        });
                    } catch (Exception e) {
                        Log.d("httpPost", "failed");
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(getBaseContext(), "Please Check Connection", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                    }
                });
                t.run();
            }
            return true;
        }
        // onPostExecute displays the results of the AsyncTask.
        protected void onPostExecute(Boolean result) {
            MainActivity.this.signalTaskComplete();
        }
    }

    private class ServerQueryTask extends UpdateTask {
        public ServerQueryTask() {
            super(MainActivity.this);
        }

        @Override
        protected void onPostExecute(Boolean result) {
            if (result) {
                sendUpdateBle(false);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        setSeekbarPositions();
                        MainActivity.this.updateLastPullTime();
                    }
                });
            }
        }
    }

    private int numTasksComplete;

    private void querySmap() {
        QueryTask task = new ServerQueryTask();
        SharedPreferences sharedPreferences = getSharedPreferences(getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        String wfmac = sharedPreferences.getString(WF_KEY, "");
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, wfmac);
        System.out.println("Querying sMAP");
//        numTasksComplete = 0;
//        for(String uuid : uuidToKey.keySet()) {
//            SmapQueryAsyncTask task =  new SmapQueryAsyncTask(uuid);
//            task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, QUERY_STRING);
//        }
    }

    private void signalTaskComplete() {
        numTasksComplete++;
        if (numTasksComplete == uuidToKey.size()) {
            sendUpdateBle(true);
        }
    }

    private void setRecurringAlarmHelper(long period, long delay, Class klass, int requestID) {
        Intent intent = new Intent(getApplicationContext(), klass);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        PendingIntent pendingIntent = PendingIntent.getBroadcast(this, requestID, intent, PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        alarmManager.setRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + delay, period, pendingIntent);
        Log.d(TAG, "Start repeating alarm");
    }

    private void setRecurringAlarm(long period, long delay) {
        setRecurringAlarmHelper(period, delay, StartServiceReceiver.class, 0);
    }

    private void setRecurringSyncAlarm(long period, long delay) {
        setRecurringAlarmHelper(period, delay, SyncTimeReceiver.class, 1);
    }

    private void setRecurringBLAlarm(long period, long delay) {
        setRecurringAlarmHelper(period, delay, BLCheckReceiver.class, 2);
    }

    /*void setBluetoothConnected(boolean connected) {
        if (connected) {

        } else {
            bluetoothManager = null;
            invalidateOptionsMenu();
        }
    }*/


    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        if (bluetoothManager == null) {
            menu.findItem(R.id.action_disconnect).setVisible(false);
            menu.findItem(R.id.action_bluetooth).setVisible(true);
        } else {
            menu.findItem(R.id.action_disconnect).setVisible(true);
            menu.findItem(R.id.action_bluetooth).setVisible(false);
        }
        return true;
    }

    private void findChair() {
        manuallyDisconnected = false;
        inMainApp = false;
        startActivity(new Intent(this, BluetoothActivity.class));
    }

    private void disconnect() {
        if (bluetoothManager != null) {
            bluetoothManager.disconnect();
            bluetoothManager = null;
            setVerifiedConnection(false);
        }
        invalidateOptionsMenu();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        switch(id) {
            case R.id.action_settings:
                return true;
            case R.id.action_bluetooth:
                findChair();
                return true;
            case R.id.action_disconnect:
                manuallyDisconnected = true;
                TextView t = (TextView) findViewById(R.id.status);
                t.setText("Status: Manually disconnected from chair.");
                disconnect();
                return true;
            case R.id.action_newchair:
                disconnect();
                manuallyDisconnected = true; // since it will try to reconnect if it gets resumed
                if (MASTER_CHAIR_CONTROL) {
                    final SharedPreferences sharedPref = this.getSharedPreferences(
                            getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
                    sharedPref.edit().putString(com.michaelchen.chairtalk.BluetoothManager.MAC_KEY, "").commit();
                    findChair();
                } else {
                    Intent i = new Intent(this, Tutorial.class);
                    startActivity(i);
                    finish();
                }
                return true;
        }

        return super.onOptionsItemSelected(item);
    }


    //Extras for some voice commands
    public void onVoiceClick(MenuItem item) {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, "en-US");
        try {
            startActivityForResult(i, REQUEST_OK);
        } catch (Exception e) {
            Toast.makeText(this, "Error initializing speech to text engine.", Toast.LENGTH_LONG).show();
        }
    }

    public void onSettingsClick(MenuItem item) {
        Intent i = new Intent(this, SettingsActivity.class);
        startActivity(i);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode==REQUEST_OK  && resultCode==RESULT_OK) {
            ArrayList<String> voiceData = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
            if (voiceData.size() > 0) {
                TextView t = (TextView) findViewById(R.id.textViewVoice);
                t.setText(voiceData.get(0));
                String text = voiceData.get(0).toLowerCase();
                if (text.contains("fan") || text.contains("hot")) {
                    coolDown();
                } else if (text.contains("heat") || text.contains("cold")) {
                    heatUp();
                }

            }
        } else if (requestCode == BLUETOOTH_REQUEST && resultCode != RESULT_OK) {
            Toast.makeText(this, "No Bluetooth Connection", Toast.LENGTH_SHORT);
        }
    }

    private static final int MAX_SEEKBAR_POS = 100;
    private static final int MIN_SEEKBAR_POS = 0;

    private void coolDown() {
        MainActivity.this.updatePref(BACK_FAN, MAX_SEEKBAR_POS);
        MainActivity.this.updatePref(BOTTOM_FAN, MAX_SEEKBAR_POS);
        MainActivity.this.updatePref(BACK_HEAT, MIN_SEEKBAR_POS);
        MainActivity.this.updatePref(BOTTOM_HEAT, MIN_SEEKBAR_POS);
        sendUpdateLocal();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                MainActivity.this.setSeekbarPositions();
            }
        });
    }

    private void heatUp() {
        MainActivity.this.updatePref(BACK_FAN, MIN_SEEKBAR_POS);
        MainActivity.this.updatePref(BOTTOM_FAN, MIN_SEEKBAR_POS);
        MainActivity.this.updatePref(BACK_HEAT, MAX_SEEKBAR_POS);
        MainActivity.this.updatePref(BOTTOM_HEAT, MAX_SEEKBAR_POS);
        sendUpdateLocal();
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                MainActivity.this.setSeekbarPositions();
            }
        });
    }
}
