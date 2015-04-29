package com.michaelchen.chairtalk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
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

    @Override
    protected void processJsonObject(JSONObject jsonResponse) {
        for(Map.Entry<String, String> entry : MainActivity.jsonToKey.entrySet()) {
            String jsonKey = entry.getKey();
            String localKey = entry.getValue();
            try {
                int value = jsonResponse.getInt(jsonKey);
                updatePref(localKey, value);
            } catch (JSONException e) {
                Log.e("JSONHandling", "json parse error", e);
            }
        }

    }

}