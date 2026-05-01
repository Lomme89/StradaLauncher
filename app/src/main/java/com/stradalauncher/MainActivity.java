package com.stradalauncher;

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
import android.content.res.Configuration;
import android.media.AudioManager;
import android.view.View;
import android.view.WindowManager;
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
        getWindow().getDecorView().setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN |
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        reapplyBrightnessFromSettings();
    }

    private void reapplyBrightnessFromSettings() {
        try {
            String json = prefs.getString("app_settings", "");
            if (json == null || json.length() <= 2) return;
            JSONObject s = new JSONObject(json);
            String theme = s.optString("theme", "dark");
            boolean isDark;
            if ("auto".equals(theme)) {
                isDark = (getResources().getConfiguration().uiMode &
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            } else {
                isDark = !"light".equals(theme);
            }
            float brightness = (float) s.optDouble(isDark ? "brightnessDark" : "brightnessLight",
                                                    isDark ? 0.5 : 0.9);
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.screenBrightness = Math.max(0.01f, Math.min(1.0f, brightness));
            getWindow().setAttributes(lp);
        } catch (Exception e) {
            Log.e(TAG, "reapplyBrightness error", e);
        }
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

        @JavascriptInterface
        public void mediaAction(String action) {
            try {
                MediaSessionManager msm = (MediaSessionManager)
                    getSystemService(Context.MEDIA_SESSION_SERVICE);
                ComponentName cn = new ComponentName(MainActivity.this, MediaListenerService.class);
                List<MediaController> controllers = msm.getActiveSessions(cn);
                if (controllers.isEmpty()) return;
                MediaController.TransportControls tc = controllers.get(0).getTransportControls();
                switch (action) {
                    case "play":   tc.play(); break;
                    case "pause":  tc.pause(); break;
                    case "next":   tc.skipToNext(); break;
                    case "prev":   tc.skipToPrevious(); break;
                    case "toggle":
                        PlaybackState ps = controllers.get(0).getPlaybackState();
                        if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) tc.pause();
                        else tc.play();
                        break;
                }
            } catch (SecurityException e) {
                // NotificationListenerService non abilitato
            } catch (Exception e) {
                Log.e(TAG, "mediaAction error", e);
            }
        }

        @JavascriptInterface
        public String getVolumeInfo() {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                int current = am.getStreamVolume(AudioManager.STREAM_MUSIC);
                int max     = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                return "{\"current\":" + current + ",\"max\":" + max + "}";
            } catch (Exception e) {
                return "{\"current\":7,\"max\":15}";
            }
        }

        @JavascriptInterface
        public void setVolume(int level) {
            try {
                AudioManager am = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
                int max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                am.setStreamVolume(AudioManager.STREAM_MUSIC,
                    Math.max(0, Math.min(level, max)), 0);
            } catch (Exception e) {
                Log.e(TAG, "setVolume error", e);
            }
        }

        @JavascriptInterface
        public void saveSettings(String json) {
            prefs.edit().putString("app_settings", json).apply();
        }

        @JavascriptInterface
        public String loadSettings() {
            return prefs.getString("app_settings", "");
        }

        @JavascriptInterface
        public void setScreenBrightness(float brightness) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = Math.max(0.01f, Math.min(1.0f, brightness));
                getWindow().setAttributes(lp);
            });
        }

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

        /**
         * Lancia un intent personalizzato da JSON.
         * Campi (tutti opzionali, almeno uno necessario):
         *   component  "com.pkg/.Activity" oppure "com.pkg/com.pkg.Activity"
         *   action     es. "android.intent.action.DIAL"
         *   data       es. "tel:" oppure "content://..."
         *   pkg        package hint (restringe la risoluzione)
         */
        @JavascriptInterface
        public void launchIntent(String json) {
            try {
                JSONObject j = new JSONObject(json);
                Intent i = new Intent();

                String action    = j.optString("action",    "");
                String component = j.optString("component", "");
                String data      = j.optString("data",      "");
                String pkg       = j.optString("pkg",       "");

                if (!action.isEmpty())    i.setAction(action);
                if (!data.isEmpty())      i.setData(android.net.Uri.parse(data));

                if (!component.isEmpty()) {
                    String[] parts = component.split("/", 2);
                    if (parts.length == 2) {
                        String cPkg   = parts[0].trim();
                        String cClass = parts[1].trim();
                        if (cClass.startsWith(".")) cClass = cPkg + cClass;
                        i.setComponent(new ComponentName(cPkg, cClass));
                    }
                } else if (!pkg.isEmpty()) {
                    i.setPackage(pkg);
                }

                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception e) {
                Log.e(TAG, "launchIntent error: " + json, e);
            }
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
    private boolean lastPlayState = false;

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
            ComponentName cn = new ComponentName(MainActivity.this, MediaListenerService.class);
            List<MediaController> controllers = msm.getActiveSessions(cn);

            if (controllers.isEmpty()) { clearMediaState(); return; }

            MediaController mc = controllers.get(0);
            MediaMetadata meta = mc.getMetadata();
            PlaybackState state = mc.getPlaybackState();
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;

            if (playing != lastPlayState) {
                lastPlayState = playing;
                runOnUiThread(() -> webView.evaluateJavascript(
                    "if(window.onPlaybackState) window.onPlaybackState(" + lastPlayState + ");", null));
            }

            if (meta != null && playing) {
                String title  = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
                String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
                String track  = (artist != null ? artist + " — " : "") + (title != null ? title : "");
                if (!track.equals(lastTrack)) {
                    lastTrack = track;
                    String artB64 = extractArtBase64(meta);
                    final String t = track, a = artB64;
                    runOnUiThread(() -> {
                        webView.evaluateJavascript(
                            "if(window.onMediaUpdate) window.onMediaUpdate(" + JSONObject.quote(t) + ");", null);
                        webView.evaluateJavascript(
                            "if(window.onAlbumArt) window.onAlbumArt(" + JSONObject.quote(a) + ");", null);
                    });
                }
            } else if (!playing && !lastTrack.isEmpty()) {
                clearMediaState();
            }
        } catch (SecurityException e) {
            // NotificationListenerService non ancora abilitato
        } catch (Exception e) {
            Log.e(TAG, "Media poll error", e);
        }
    }

    private String extractArtBase64(MediaMetadata meta) {
        try {
            Bitmap art = meta.getBitmap(MediaMetadata.METADATA_KEY_ART);
            if (art == null) art = meta.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
            if (art == null) return "";
            Bitmap scaled = Bitmap.createScaledBitmap(art, 80, 80, true);
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 65, bos);
            return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    private void clearMediaState() {
        if (!lastTrack.isEmpty() || lastPlayState) {
            lastTrack = ""; lastPlayState = false;
            runOnUiThread(() -> {
                webView.evaluateJavascript("if(window.onMediaUpdate) window.onMediaUpdate('');", null);
                webView.evaluateJavascript("if(window.onAlbumArt) window.onAlbumArt('');", null);
                webView.evaluateJavascript("if(window.onPlaybackState) window.onPlaybackState(false);", null);
            });
        }
    }

    // ─── BACK BUTTON ─────────────────────────────────────────────────────────
    @Override
    public void onBackPressed() {
        // Il launcher non deve tornare indietro — rimane fermo
        // (opzionale: potresti mostrare un menu)
    }
}
