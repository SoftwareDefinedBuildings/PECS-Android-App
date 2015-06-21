package com.michaelchen.chairtalk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.preference.PreferenceManager;
import android.util.Log;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Created by michael on 4/27/15.
 */
public class UpdateTask extends QueryTask {
    private Context context;

    UpdateTask(Context context) {
        this.context = context;
    }

    protected boolean updatePref(String key, int value) {
        // update heating or cooling
        SharedPreferences sharedPref = context.getSharedPreferences(
                context.getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sharedPref.edit();
        e.putInt(key, value);
        e.apply();
        return e.commit();
    }

    protected boolean updatePref(String key, double value) {
        // update heating or cooling
        SharedPreferences sharedPref = context.getSharedPreferences(
                context.getString(R.string.temp_preference_file_key), Context.MODE_PRIVATE);
        SharedPreferences.Editor e = sharedPref.edit();
        e.putLong(key, Double.doubleToRawLongBits(value));
        e.apply();
        return e.commit();
    }

    @Override
    protected boolean processJsonObject(JSONObject jsonResponse) {
        boolean ret = false;
        int currentTime = 0;
        double prevUpdateTime = Double.longBitsToDouble(context.getSharedPreferences(context.getString(
                R.string.temp_preference_file_key), Context.MODE_PRIVATE).getLong(MainActivity.LAST_TIME, 0));
        try {
            double readingTime = jsonResponse.getDouble("time");
            if (readingTime < prevUpdateTime) {
                // ignore; we have a more recent version
                System.out.println("ignoring; we have a more recent version! " + readingTime + " " + prevUpdateTime);
                return false;
            }
        } catch (JSONException jsone) {
            Log.e("JSONHandling", "json parse error", jsone);
        }
        System.out.println("Good timestamp: processing...");
        ret = true;
        for(Map.Entry<String, String> entry : MainActivity.jsonToKey.entrySet()) {
            String jsonKey = entry.getKey();
            String localKey = entry.getValue();

            try {
                if (localKey.equals(MainActivity.LAST_TIME)) { // jsonKey is "time"
                    double value = jsonResponse.getDouble(jsonKey);
                    updatePref(localKey, value);
                } else {
                    int value = jsonResponse.getInt(jsonKey);
                    updatePref(localKey, value);
                }
            } catch (JSONException e) {
                Log.e("JSONHandling", "json parse error", e);
            }
        }

        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(context);
        String name = sp.getString(SettingsActivity.NAME, "");

        return ret;
    }

}
