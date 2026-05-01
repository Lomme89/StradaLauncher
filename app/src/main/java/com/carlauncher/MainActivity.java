package com.carlauncher;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CarLauncher";
    private static final int PERM_LOCATION = 100;

    private WebView webView;
    private LocationManager locationManager;
    private SharedPreferences prefs;

    // ─── LOCATION LISTENER ───────────────────────────────────────────────────
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(Location location) {
            double speedMs = location.hasSpeed() ? location.getSpeed() : 0;
            double speedKmh = speedMs * 3.6;
            runOnUiThread(() ->
                webView.evaluateJavascript(
                    "if(window.onGpsSpeed) window.onGpsSpeed(" + speedKmh + ");", null)
            );
        }
        @Override public void onProviderEnabled(String p) {}
        @Override public void onProviderDisabled(String p) {}
        @Override public void onStatusChanged(String p, int s, Bundle e) {}
    };

    // ─── LIFECYCLE ───────────────────────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fullscreen immersivo
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("CarLauncherPrefs", Context.MODE_PRIVATE);
        webView = findViewById(R.id.webview);

        setupWebView();
        requestLocationPermission();
        startMediaPolling();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Fullscreen anche al resume (es. dopo aver aperto un'app)
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
    }

    // ─── WEBVIEW SETUP ───────────────────────────────────────────────────────
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);

        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient());

        // Espone il bridge "Android" a JavaScript
        webView.addJavascriptInterface(new JsBridge(), "Android");

        // Carica l'HTML dagli assets
        webView.loadUrl("file:///android_asset/launcher.html");
    }

    // ─── JAVASCRIPT BRIDGE ───────────────────────────────────────────────────
    private class JsBridge {

        /**
         * Restituisce la lista delle app installate come JSON array.
         * Ogni elemento: { label, packageName, icon (base64 PNG) }
         */
        @JavascriptInterface
        public String getInstalledApps() {
            try {
                PackageManager pm = getPackageManager();
                Intent intent = new Intent(Intent.ACTION_MAIN, null);
                intent.addCategory(Intent.CATEGORY_LAUNCHER);
                List<ResolveInfo> apps = pm.queryIntentActivities(intent, 0);

                JSONArray arr = new JSONArray();
                for (ResolveInfo info : apps) {
                    JSONObject obj = new JSONObject();
                    obj.put("label", info.loadLabel(pm).toString());
                    obj.put("packageName", info.activityInfo.packageName);

                    // Icona → base64
                    try {
                        Drawable d = info.loadIcon(pm);
                        Bitmap bmp = Bitmap.createBitmap(
                            d.getIntrinsicWidth() > 0 ? d.getIntrinsicWidth() : 96,
                            d.getIntrinsicHeight() > 0 ? d.getIntrinsicHeight() : 96,
                            Bitmap.Config.ARGB_8888);
                        Canvas c = new Canvas(bmp);
                        d.setBounds(0, 0, c.getWidth(), c.getHeight());
                        d.draw(c);
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        bmp.compress(Bitmap.CompressFormat.PNG, 80, bos);
                        String b64 = Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
                        obj.put("icon", "data:image/png;base64," + b64);
                    } catch (Exception ignored) {
                        obj.put("icon", "");
                    }

                    arr.put(obj);
                }
                return arr.toString();
            } catch (Exception e) {
                Log.e(TAG, "getInstalledApps error", e);
                return "[]";
            }
        }

        /**
         * Lancia un'app tramite packageName.
         */
        @JavascriptInterface
        public void launchApp(String packageName) {
            try {
                Intent i = getPackageManager().getLaunchIntentForPackage(packageName);
                if (i != null) {
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(i);
                }
            } catch (Exception e) {
                Log.e(TAG, "launchApp error: " + packageName, e);
            }
        }

        /**
         * Salva la configurazione pulsanti come JSON string.
         */
        @JavascriptInterface
        public void saveButtonConfig(String json) {
            prefs.edit().putString("button_config", json).apply();
        }

        /**
         * Legge la configurazione pulsanti salvata.
         */
        @JavascriptInterface
        public String loadButtonConfig() {
            return prefs.getString("button_config", "");
        }

        /**
         * Apre le impostazioni di sistema Android.
         */
        @JavascriptInterface
        public void openSettings(String type) {
            String action;
            switch (type) {
                case "display": action = android.provider.Settings.ACTION_DISPLAY_SETTINGS; break;
                case "sound":   action = android.provider.Settings.ACTION_SOUND_SETTINGS; break;
                case "wifi":    action = android.provider.Settings.ACTION_WIFI_SETTINGS; break;
                case "bt":      action = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS; break;
                default:        action = android.provider.Settings.ACTION_SETTINGS; break;
            }
            Intent i = new Intent(action);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }
    }

    // ─── GPS ─────────────────────────────────────────────────────────────────
    private void requestLocationPermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                new String[]{ Manifest.permission.ACCESS_FINE_LOCATION }, PERM_LOCATION);
        } else {
            startGps();
        }
    }

    private void startGps() {
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        try {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER, 1000, 0, locationListener);
            }
        } catch (Exception e) {
            Log.e(TAG, "GPS error", e);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == PERM_LOCATION &&
                results.length > 0 &&
                results[0] == PackageManager.PERMISSION_GRANTED) {
            startGps();
        }
    }

    // ─── MEDIA SESSION POLLING ───────────────────────────────────────────────
    // Nota: per MediaSessionManager serve NotificationListenerService.
    // Qui usiamo un polling leggero sull'AudioManager come fallback universale.
    // Per l'integrazione completa vedi MediaListenerService.java.
    private final Handler mediaHandler = new Handler(Looper.getMainLooper());
    private String lastTrack = "";

    private void startMediaPolling() {
        mediaHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                pollMediaSession();
                mediaHandler.postDelayed(this, 2000);
            }
        }, 2000);
    }

    private void pollMediaSession() {
        try {
            MediaSessionManager msm = (MediaSessionManager)
                getSystemService(Context.MEDIA_SESSION_SERVICE);
            // Richiede NotificationListenerService attivo (vedi setup)
            ComponentName cn = new ComponentName(MainActivity.this,
                MediaListenerService.class);
            List<MediaController> controllers = msm.getActiveSessions(cn);
            if (!controllers.isEmpty()) {
                MediaController mc = controllers.get(0);
                MediaMetadata meta = mc.getMetadata();
                PlaybackState state = mc.getPlaybackState();
                if (meta != null && state != null &&
                        state.getState() == PlaybackState.STATE_PLAYING) {
                    String title = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
                    String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                    String track = (artist != null ? artist + " — " : "") +
                                   (title != null ? title : "");
                    if (!track.equals(lastTrack)) {
                        lastTrack = track;
                        final String t = track;
                        runOnUiThread(() ->
                            webView.evaluateJavascript(
                                "if(window.onMediaUpdate) window.onMediaUpdate(" +
                                JSONObject.quote(t) + ");", null)
                        );
                    }
                } else if (state == null ||
                           state.getState() != PlaybackState.STATE_PLAYING) {
                    if (!lastTrack.equals("")) {
                        lastTrack = "";
                        runOnUiThread(() ->
                            webView.evaluateJavascript(
                                "if(window.onMediaUpdate) window.onMediaUpdate('');", null)
                        );
                    }
                }
            }
        } catch (SecurityException e) {
            // NotificationListenerService non ancora abilitato — silenzioso
        } catch (Exception e) {
            Log.e(TAG, "Media poll error", e);
        }
    }

    // ─── BACK BUTTON ─────────────────────────────────────────────────────────
    @Override
    public void onBackPressed() {
        // Il launcher non deve tornare indietro — rimane fermo
        // (opzionale: potresti mostrare un menu)
    }
}
