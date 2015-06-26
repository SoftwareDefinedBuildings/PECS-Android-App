package com.michaelchen.chairtalk;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Created by Sam on 6/25/2015.
 */

public class SyncTimeReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d("SyncTimeReceiver", "SYNCHRONIZING TIME");
        if (MainActivity.currActivity != null) {
            MainActivity.currActivity.synchronizeTimeAsync();
        }
    }
}
