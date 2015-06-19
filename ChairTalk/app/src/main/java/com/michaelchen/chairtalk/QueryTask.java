package com.michaelchen.chairtalk;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.util.Log;

import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.DefaultHttpClient;
import org.json.JSONObject;

import java.io.InputStream;

/**
 * Created by michael on 4/29/15.
 */
abstract class QueryTask extends AsyncTask<String, Void, Boolean> {

    static final String uri = MainActivity.uri;
    public static final String FAILURE = "";

    @Override
    protected Boolean doInBackground(String... params) {
        try {
            DefaultHttpClient httpclient = new DefaultHttpClient();
            String wfMac = params[0];
            if (wfMac == "") {
                System.out.println("Unknown Node ID, skipping getting stats");
                return false;
            }
            HttpGet request = new HttpGet(uri + "?macaddr=" + wfMac);
            HttpResponse httpResponse = httpclient.execute(request);
            InputStream inputStream = httpResponse.getEntity().getContent();
            final String response = MainActivity.inputStreamToString(inputStream);
            Log.d("httpGet", response);
            JSONObject jsonResponse = new JSONObject(response);
            return processJsonObject(jsonResponse);

        } catch (Exception e) {
            Log.e("HTTPGet", "failed", e);
            return false;
        }
    }

    protected abstract boolean processJsonObject(JSONObject jsonResponse);
}
