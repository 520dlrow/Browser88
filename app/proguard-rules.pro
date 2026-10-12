# Browser 88 — no obfuscation needed for the debug build
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
