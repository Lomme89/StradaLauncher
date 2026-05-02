# Keep WebView JS bridge methods
-keepclassmembers class com.stradalauncher.MainActivity$JsBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep all app classes (Activity, Service, BroadcastReceiver)
-keep class com.stradalauncher.** { *; }
