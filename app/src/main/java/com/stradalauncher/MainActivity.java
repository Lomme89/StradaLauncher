package com.stradalauncher;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.content.SharedPreferences;
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
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.media.AudioManager;
import android.view.View;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "CarLauncher";
    private static final int PERM_LOCATION = 100;
    private static final String APPS_CACHE_FILE = "apps_cache.json";
    private static final String APPS_HASH_KEY   = "apps_hash";
    private static final int    ICON_THREADS    = 3;

    private WebView webView;
    private LocationManager locationManager;
    private SharedPreferences prefs;
    private final ExecutorService appsExecutor  = Executors.newSingleThreadExecutor();
    private final ExecutorService mediaExecutor = Executors.newSingleThreadExecutor();
    private BroadcastReceiver packageReceiver;
    private MediaSessionManager msmCached;
    private volatile String appsJsonMemCache = null;
    private volatile String appsHashCache    = null;
    private volatile float  cachedBrightness = -1f;
    private volatile MediaController activeController = null;
    private MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener;
    private long pollIntervalMs = 2000L;
    private static final long POLL_MIN_MS  = 2000L;
    private static final long POLL_MAX_MS  = 15000L;

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
        msmCached = (MediaSessionManager) getSystemService(Context.MEDIA_SESSION_SERVICE);

        setupWebView();
        requestLocationPermission();
        startMediaPolling();
        registerPackageReceiver();
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
        boolean canWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(this);
        webView.post(() -> webView.evaluateJavascript(
            "if(window.onWriteSettingsStatus) window.onWriteSettingsStatus(" + canWrite + ");", null));
    }

    private void reapplyBrightnessFromSettings() {
        if (cachedBrightness < 0) {
            String json = prefs.getString("app_settings", "");
            if (json != null && json.length() > 2) updateBrightnessCache(json);
            if (cachedBrightness < 0) return;
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = Math.max(0.01f, Math.min(1.0f, cachedBrightness));
        getWindow().setAttributes(lp);
    }

    private void updateBrightnessCache(String json) {
        try {
            JSONObject s = new JSONObject(json);
            String theme = s.optString("theme", "dark");
            boolean isDark;
            if ("auto".equals(theme)) {
                isDark = (getResources().getConfiguration().uiMode &
                    Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            } else {
                isDark = !"light".equals(theme);
            }
            cachedBrightness = (float) s.optDouble(
                isDark ? "brightnessDark" : "brightnessLight", isDark ? 0.5 : 0.9);
        } catch (Exception ignored) {}
    }

    // ─── WEBVIEW SETUP ───────────────────────────────────────────────────────
    private void setupWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setCacheMode(WebSettings.LOAD_CACHE_ELSE_NETWORK);
        s.setBlockNetworkLoads(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try { android.webkit.WebView.setSafeBrowsingWhitelist(java.util.Collections.emptyList(), null); } catch (Throwable ignored) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            s.setOffscreenPreRaster(true);
            try { s.setSafeBrowsingEnabled(false); } catch (Throwable ignored) {}
        }

        webView.setBackgroundColor(android.graphics.Color.BLACK);
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
        webView.setWebChromeClient(new WebChromeClient());
        webView.setWebViewClient(new WebViewClient() {
            @Override
            @RequiresApi(Build.VERSION_CODES.O)
            public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
                Log.w(TAG, "WebView render process gone, reloading");
                view.loadUrl("file:///android_asset/launcher.html");
                return true;
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                boolean enabled = isNotificationListenerEnabledInternal();
                boolean canWrite = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.System.canWrite(MainActivity.this);
                view.postDelayed(() -> {
                    view.evaluateJavascript(
                        "if(window.onNotificationListenerStatus) window.onNotificationListenerStatus(" + enabled + ");", null);
                    view.evaluateJavascript(
                        "if(window.onWriteSettingsStatus) window.onWriteSettingsStatus(" + canWrite + ");", null);
                }, 600);
            }
        });

        webView.addJavascriptInterface(new JsBridge(), "Android");
        webView.loadUrl("file:///android_asset/launcher.html");
    }

    private void registerPackageReceiver() {
        packageReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                Uri data = intent.getData();
                if (data != null && getPackageName().equals(data.getSchemeSpecificPart())) return;
                appsExecutor.execute(() -> refreshAppsCache());
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_PACKAGE_ADDED);
        f.addAction(Intent.ACTION_PACKAGE_REMOVED);
        f.addDataScheme("package");
        registerReceiver(packageReceiver, f);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (packageReceiver != null) {
            try { unregisterReceiver(packageReceiver); } catch (Exception ignored) {}
        }
        if (sessionsChangedListener != null && msmCached != null) {
            try { msmCached.removeOnActiveSessionsChangedListener(sessionsChangedListener); } catch (Exception ignored) {}
            sessionsChangedListener = null;
        }
        mediaHandler.removeCallbacksAndMessages(null);
        appsExecutor.shutdown();
        mediaExecutor.shutdown();
    }

    // ─── JAVASCRIPT BRIDGE ───────────────────────────────────────────────────
    private class JsBridge {

        /**
         * Ritorna la lista app dalla cache SharedPreferences (fast path).
         * Lancia un refresh in background che aggiorna la cache e chiama
         * window.onAppsReady(json) via evaluateJavascript quando finisce.
         */
        @JavascriptInterface
        public String getInstalledApps() {
            String cached = appsJsonMemCache != null ? appsJsonMemCache : readAppsCache();
            appsExecutor.execute(() -> refreshAppsCache());
            return cached.isEmpty() ? "[]" : cached;
        }

        /**
         * Restituisce le Activity esportate di un'app come JSON array.
         * Ogni elemento: { label, name, component }
         */
        @JavascriptInterface
        public String getAppActivities(String packageName) {
            try {
                PackageManager pm = getPackageManager();
                android.content.pm.PackageInfo pi =
                    pm.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES);

                JSONArray arr = new JSONArray();
                if (pi.activities == null) return arr.toString();

                for (android.content.pm.ActivityInfo activity : pi.activities) {
                    if (!activity.exported) continue;

                    JSONObject obj = new JSONObject();
                    CharSequence label = activity.loadLabel(pm);
                    String labelText = label != null ? label.toString() : "";
                    if (labelText.trim().isEmpty()) labelText = activity.name;

                    obj.put("label", labelText);
                    obj.put("name", activity.name);
                    obj.put("component", activity.packageName + "/" + activity.name);
                    arr.put(obj);
                }
                return arr.toString();
            } catch (Exception e) {
                Log.e(TAG, "getAppActivities error: " + packageName, e);
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
                MediaController mc = activeController;
                if (mc == null) {
                    // try opportunistic refresh once
                    refreshActiveControllerNow();
                    mc = activeController;
                    if (mc == null) return;
                }
                MediaController.TransportControls tc = mc.getTransportControls();
                switch (action) {
                    case "play":   tc.play(); break;
                    case "pause":  tc.pause(); break;
                    case "next":   tc.skipToNext(); break;
                    case "prev":   tc.skipToPrevious(); break;
                    case "toggle":
                        PlaybackState ps = mc.getPlaybackState();
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
            updateBrightnessCache(json);
        }

        @JavascriptInterface
        public String loadSettings() {
            return prefs.getString("app_settings", "");
        }

        @JavascriptInterface
        public void setScreenBrightness(float brightness) {
            final float clamped = Math.max(0.01f, Math.min(1.0f, brightness));
            // window param flip is cheap, keep on UI thread
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = clamped;
                getWindow().setAttributes(lp);
            });
            // Settings.System.putInt() can do disk I/O — push off-thread
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && Settings.System.canWrite(MainActivity.this)) {
                appsExecutor.execute(() -> {
                    try {
                        Settings.System.putInt(getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS_MODE,
                            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                        Settings.System.putInt(getContentResolver(),
                            Settings.System.SCREEN_BRIGHTNESS,
                            Math.round(clamped * 255));
                    } catch (Throwable t) {
                        Log.w(TAG, "setScreenBrightness write failed", t);
                    }
                });
            }
        }

        @JavascriptInterface
        public boolean canControlSystemBrightness() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return Settings.System.canWrite(MainActivity.this);
            }
            return true;
        }

        @JavascriptInterface
        public void requestWriteSettings() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.System.canWrite(MainActivity.this)) {
                Intent intent = new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    Uri.parse("package:" + getPackageName()));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
            }
        }

        @JavascriptInterface
        public boolean isNotificationListenerEnabled() {
            return isNotificationListenerEnabledInternal();
        }

        @JavascriptInterface
        public void openNotificationListenerSettings() {
            Intent i = new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
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

    // ─── NOTIFICATION LISTENER PERMISSION ───────────────────────────────────
    private boolean isNotificationListenerEnabledInternal() {
        String flat = android.provider.Settings.Secure.getString(
            getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    // ─── APPS CACHE ──────────────────────────────────────────────────────────
    private void refreshAppsCache() {
        try {
            PackageManager pm = getPackageManager();
            Intent intent = new Intent(Intent.ACTION_MAIN, null);
            intent.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps = pm.queryIntentActivities(intent, 0);

            String hash = computeAppsHash(apps);
            if (hash.equals(loadAppsHash()) && appsJsonMemCache != null) {
                final String cached = appsJsonMemCache;
                webView.post(() -> webView.evaluateJavascript(
                    "if(window.onAppsReady) window.onAppsReady(" + cached + ");", null));
                return;
            }

            String json = buildAppsJson(apps, pm);
            appsJsonMemCache = json;
            saveAppsHash(hash);
            writeAppsCache(json);
            final String js = "if(window.onAppsReady) window.onAppsReady(" + json + ");";
            webView.post(() -> webView.evaluateJavascript(js, null));
        } catch (Exception e) {
            Log.e(TAG, "refreshAppsCache error", e);
        }
    }

    private static final int ICON_PX = 64;
    private String buildAppsJson(List<ResolveInfo> apps, PackageManager pm) throws Exception {
        ExecutorService iconPool = Executors.newFixedThreadPool(ICON_THREADS);
        try {
            List<Future<JSONObject>> futures = new ArrayList<>(apps.size());
            for (ResolveInfo info : apps) {
                futures.add(iconPool.submit(() -> {
                    try {
                        JSONObject obj = new JSONObject();
                        obj.put("label", info.loadLabel(pm).toString());
                        obj.put("packageName", info.activityInfo.packageName);
                        try {
                            Drawable d = info.loadIcon(pm);
                            Bitmap bmp = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888);
                            Canvas c = new Canvas(bmp);
                            d.setBounds(0, 0, ICON_PX, ICON_PX);
                            d.draw(c);
                            ByteArrayOutputStream bos = new ByteArrayOutputStream(3072);
                            String mime;
                            if (Build.VERSION.SDK_INT >= 30) {
                                bmp.compress(Bitmap.CompressFormat.WEBP_LOSSY, 70, bos);
                                mime = "image/webp";
                            } else {
                                bmp.compress(Bitmap.CompressFormat.WEBP, 70, bos);
                                mime = "image/webp";
                            }
                            bmp.recycle();
                            obj.put("icon", "data:" + mime + ";base64," +
                                Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP));
                        } catch (Throwable ignored) {
                            obj.put("icon", "");
                        }
                        return obj;
                    } catch (Throwable t) {
                        Log.w(TAG, "buildAppsJson: skip " + info.activityInfo.packageName, t);
                        return null;
                    }
                }));
            }
            JSONArray arr = new JSONArray();
            for (Future<JSONObject> f : futures) {
                JSONObject obj = f.get();
                if (obj != null) arr.put(obj);
            }
            return arr.toString();
        } finally {
            iconPool.shutdown();
        }
    }

    private String computeAppsHash(List<ResolveInfo> apps) {
        List<String> pkgs = new ArrayList<>(apps.size());
        for (ResolveInfo info : apps) pkgs.add(info.activityInfo.packageName);
        Collections.sort(pkgs);
        int hash = pkgs.size();
        for (String pkg : pkgs) hash = hash * 31 + pkg.hashCode();
        return String.valueOf(hash);
    }

    private String loadAppsHash() {
        if (appsHashCache != null) return appsHashCache;
        appsHashCache = prefs.getString(APPS_HASH_KEY, "");
        return appsHashCache;
    }

    private void saveAppsHash(String hash) {
        appsHashCache = hash;
        prefs.edit().putString(APPS_HASH_KEY, hash).apply();
    }

    private String readAppsCache() {
        try {
            File f = new File(getFilesDir(), APPS_CACHE_FILE);
            if (!f.exists()) return "";
            FileInputStream fis = new FileInputStream(f);
            byte[] data = new byte[(int) f.length()];
            fis.read(data);
            fis.close();
            return new String(data, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private void writeAppsCache(String json) {
        try {
            File f = new File(getFilesDir(), APPS_CACHE_FILE);
            FileOutputStream fos = new FileOutputStream(f);
            fos.write(json.getBytes("UTF-8"));
            fos.close();
        } catch (Exception e) {
            Log.e(TAG, "writeAppsCache error", e);
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

    private final Runnable mediaPollRunnable = new Runnable() {
        @Override
        public void run() {
            mediaExecutor.execute(() -> pollMediaSession());
            mediaHandler.postDelayed(this, pollIntervalMs);
        }
    };

    private void startMediaPolling() {
        registerSessionsListener();
        refreshActiveControllerNow();
        mediaHandler.postDelayed(mediaPollRunnable, 500);
    }

    private void registerSessionsListener() {
        if (sessionsChangedListener != null || msmCached == null) return;
        sessionsChangedListener = new MediaSessionManager.OnActiveSessionsChangedListener() {
            @Override
            public void onActiveSessionsChanged(List<MediaController> controllers) {
                activeController = (controllers != null && !controllers.isEmpty()) ? controllers.get(0) : null;
                pollIntervalMs = POLL_MIN_MS;
                mediaHandler.removeCallbacks(mediaPollRunnable);
                mediaHandler.post(mediaPollRunnable);
            }
        };
        try {
            ComponentName cn = new ComponentName(MainActivity.this, MediaListenerService.class);
            msmCached.addOnActiveSessionsChangedListener(sessionsChangedListener, cn, mediaHandler);
        } catch (SecurityException ignored) {
            // listener service not enabled — caught later in poll
        } catch (Exception e) {
            Log.w(TAG, "addOnActiveSessionsChangedListener failed", e);
        }
    }

    private void refreshActiveControllerNow() {
        try {
            if (msmCached == null) return;
            ComponentName cn = new ComponentName(MainActivity.this, MediaListenerService.class);
            List<MediaController> controllers = msmCached.getActiveSessions(cn);
            activeController = (controllers != null && !controllers.isEmpty()) ? controllers.get(0) : null;
        } catch (SecurityException ignored) {
        } catch (Exception e) {
            Log.w(TAG, "refreshActiveControllerNow failed", e);
        }
    }

    private void pollMediaSession() {
        try {
            MediaController mc = activeController;
            if (mc == null) {
                clearMediaState();
                pollIntervalMs = Math.min(POLL_MAX_MS, pollIntervalMs * 2L);
                return;
            }
            pollIntervalMs = POLL_MIN_MS;

            MediaMetadata meta = mc.getMetadata();
            PlaybackState state = mc.getPlaybackState();
            boolean playing = state != null && state.getState() == PlaybackState.STATE_PLAYING;

            String title  = meta != null ? meta.getString(MediaMetadata.METADATA_KEY_TITLE)  : null;
            String artist = meta != null ? meta.getString(MediaMetadata.METADATA_KEY_ARTIST) : null;
            String titleSafe  = title  != null ? title  : "";
            String artistSafe = artist != null ? artist : "";
            String trackKey   = artistSafe + "—" + titleSafe;
            boolean trackChanged = meta != null && !trackKey.equals(lastTrack);
            boolean playStateChanged = playing != lastPlayState;

            if (!trackChanged && !playStateChanged && meta != null) return;
            if (meta == null && !lastTrack.isEmpty()) {
                clearMediaState();
                return;
            }

            StringBuilder js = new StringBuilder(256);
            if (playStateChanged) {
                lastPlayState = playing;
                js.append("if(window.onPlaybackState) window.onPlaybackState(").append(playing).append(");");
            }
            if (trackChanged) {
                lastTrack = trackKey;
                String artB64 = extractArtBase64(meta);
                js.append("if(window.onMediaUpdate) window.onMediaUpdate(")
                  .append(JSONObject.quote(titleSafe)).append(",").append(JSONObject.quote(artistSafe)).append(");")
                  .append("if(window.onAlbumArt) window.onAlbumArt(").append(JSONObject.quote(artB64)).append(");");
            }
            if (js.length() == 0) return;
            final String script = js.toString();
            runOnUiThread(() -> webView.evaluateJavascript(script, null));
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
            int maxSize = 300;
            int w = art.getWidth(), h = art.getHeight();
            Bitmap scaled;
            if (w > maxSize || h > maxSize) {
                float ratio = Math.min((float) maxSize / w, (float) maxSize / h);
                scaled = Bitmap.createScaledBitmap(art, Math.round(w * ratio), Math.round(h * ratio), true);
            } else {
                scaled = art;
            }
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 70, bos);
            return "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP);
        } catch (Exception e) { return ""; }
    }

    private void clearMediaState() {
        if (!lastTrack.isEmpty() || lastPlayState) {
            lastTrack = ""; lastPlayState = false;
            final String script =
                "if(window.onMediaUpdate) window.onMediaUpdate('','');" +
                "if(window.onAlbumArt) window.onAlbumArt('');" +
                "if(window.onPlaybackState) window.onPlaybackState(false);";
            runOnUiThread(() -> webView.evaluateJavascript(script, null));
        }
    }

    // ─── BACK BUTTON ─────────────────────────────────────────────────────────
    @Override
    public void onBackPressed() {
        webView.evaluateJavascript(
            "window.closeTopOverlay ? window.closeTopOverlay() : false", null);
    }
}
