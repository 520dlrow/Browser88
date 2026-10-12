package com.ctoa.browser88;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.CookieManager;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.Toast;

import androidx.core.app.NotificationCompat;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends Activity {

    private static final int REQ_FILE       = 1001;
    private static final int REQ_PERMS      = 2001;
    private static final int REQ_NOTIF      = 2002;

    private static final String CHANNEL_ID  = "b88.pages";
    private static final String DESKTOP_UA  =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final Set<String> AD_HOSTS = new HashSet<>(Arrays.asList(
        "doubleclick.net","googleadservices.com","googlesyndication.com",
        "google-analytics.com","googletagservices.com","googletagmanager.com",
        "adservice.google.com","adsafeprotected.com","adnxs.com","adsrvr.org",
        "amazon-adsystem.com","outbrain.com","taboola.com","criteo.com",
        "criteo.net","pubmatic.com","rubiconproject.com","openx.net",
        "casalemedia.com","moatads.com","scorecardresearch.com",
        "quantserve.com","hotjar.com","mixpanel.com","segment.com",
        "segment.io","connect.facebook.net","graph.facebook.com",
        "analytics.twitter.com","static.ads-twitter.com","bat.bing.com",
        "clarity.ms","mc.yandex.ru","yandex.ru"
    ));

    private FrameLayout root;
    private WebView contentView;
    private ShellWebView shellView;

    private boolean sheetOpen = false;
    private boolean adBlockEnabled = true;
    private boolean desktopMode = false;
    private int blockedCount = 0;
    private String lastUrl = "";

    private ValueCallback<Uri[]> filePathCallback;

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.parseColor("#0f0d09"));

        contentView = new WebView(this);
        configureWebView(contentView, false);
        contentView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        setupContentClient();
        contentView.loadUrl("file:///android_asset/home.html");
        root.addView(contentView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        shellView = new ShellWebView(this);
        configureWebView(shellView, true);
        shellView.addJavascriptInterface(new Bridge(), "AndroidBridge");
        shellView.loadUrl("file:///android_asset/index.html");
        root.addView(shellView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(root);
        createNotifChannel();
        requestPerms();
    }

    /* ---- transparent shell on top, only top-bar + dock get touches ---- */
    private class ShellWebView extends WebView {
        public ShellWebView(Context c){ super(c); }
        @Override
        public boolean dispatchTouchEvent(MotionEvent ev){
            if(sheetOpen) return super.dispatchTouchEvent(ev);
            float y = ev.getY();
            float h = getHeight();
            float topPx  = dp(64) + getSafeTop();
            float dockPx = dp(64) + getSafeBottom();
            if(y < topPx || y > h - dockPx){
                return super.dispatchTouchEvent(ev);
            }
            return false;
        }
    }

    private int dp(int v){
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private int getSafeTop(){
        if(Build.VERSION.SDK_INT >= 23){
            android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
            if(wi != null) return wi.getSystemWindowInsetTop();
        }
        return 0;
    }

    private int getSafeBottom(){
        if(Build.VERSION.SDK_INT >= 23){
            android.view.WindowInsets wi = getWindow().getDecorView().getRootWindowInsets();
            if(wi != null) return wi.getSystemWindowInsetBottom();
        }
        return 0;
    }

    /* ---- webview base setup ---- */
    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebView wv, boolean transparent){
        WebSettings s = wv.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(false);

        CookieManager.getInstance().setAcceptCookie(true);
        if(Build.VERSION.SDK_INT >= 21){
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true);
        }

        if(transparent){
            wv.setBackgroundColor(Color.TRANSPARENT);
            wv.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
        } else {
            wv.setBackgroundColor(Color.parseColor("#0f0d09"));
        }
    }

    private boolean isAdHost(String host){
        if(host == null) return false;
        String h = host.toLowerCase();
        for(String ad : AD_HOSTS){
            if(h.equals(ad) || h.endsWith("." + ad)) return true;
        }
        return false;
    }

    /* ---- desktop mode: UA + viewport meta injection ---- */
    private void applyDesktopMode(boolean on){
        desktopMode = on;
        WebSettings s = contentView.getSettings();
        if(on){
            s.setUserAgentString(DESKTOP_UA);
            s.setLoadWithOverviewMode(false);
            s.setUseWideViewPort(false);
        } else {
            s.setUserAgentString(null);
            s.setLoadWithOverviewMode(true);
            s.setUseWideViewPort(true);
        }
        String url = contentView.getUrl();
        if(url != null && !url.startsWith("file://")) contentView.reload();
    }

    private void injectDesktopViewport(WebView v){
        if(!desktopMode) return;
        String js =
            "(function(){try{" +
            "var m=document.querySelector('meta[name=\"viewport\"]');" +
            "if(!m){m=document.createElement('meta');m.name='viewport';" +
            "(document.head||document.documentElement).appendChild(m);}" +
            "m.setAttribute('content','width=1280');" +
            "}catch(e){}})();";
        v.evaluateJavascript(js, null);
    }

    /* ---- notification channel ---- */
    private void createNotifChannel(){
        if(Build.VERSION.SDK_INT >= 26){
            NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                "Web notifications",
                NotificationManager.IMPORTANCE_DEFAULT);
            ch.setDescription("Notifications sent by pages you visit");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if(nm != null) nm.createNotificationChannel(ch);
        }
    }

    private void postNotification(String title, String body){
        if(Build.VERSION.SDK_INT >= 33){
            if(checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED){
                requestPermissions(
                    new String[]{ Manifest.permission.POST_NOTIFICATIONS },
                    REQ_NOTIF);
                return;
            }
        }
        try {
            Intent open = new Intent(this, MainActivity.class);
            PendingIntent pi = PendingIntent.getActivity(
                this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

            NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title != null ? title : "Browser 88")
                .setContentText(body != null ? body : "")
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setContentIntent(pi)
                .setAutoCancel(true);

            NotificationManager nm = getSystemService(NotificationManager.class);
            if(nm != null) nm.notify((int) System.currentTimeMillis(), b.build());
        } catch(Exception e){
            Toast.makeText(this, title + ": " + body, Toast.LENGTH_SHORT).show();
        }
    }

    /* ---- content webview client ---- */
    private void setupContentClient(){

        contentView.setWebChromeClient(new WebChromeClient() {

            @Override
            public void onProgressChanged(WebView v, int p){
                callShellBool("shellSetProgress", p < 100);
            }

            @Override
            public void onReceivedTitle(WebView v, String title){
                String url = v.getUrl();
                if(url != null && !url.startsWith("file://")){
                    callShellTwo("shellOnPageLoaded", jsStr(url), jsStr(title != null ? title : ""));
                }
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams params){
                if(filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                    return true;
                } catch(Exception e){
                    filePathCallback = null;
                    return false;
                }
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request){
                runOnUiThread(new Runnable(){
                    @Override public void run(){
                        try { request.grant(request.getResources()); }
                        catch(Exception e){ request.deny(); }
                    }
                });
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback cb){
                cb.invoke(origin, true, false);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm){
                android.util.Log.d("Browser88", cm.message());
                return true;
            }

            @Override
            public boolean onJsAlert(WebView v, String u, String m, final android.webkit.JsResult r){
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(m)
                    .setPositiveButton(android.R.string.ok, null)
                    .setCancelable(false).show();
                r.confirm();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String u, String m, final android.webkit.JsResult r){
                new AlertDialog.Builder(MainActivity.this)
                    .setMessage(m)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setCancelable(false).show();
                r.confirm();
                return true;
            }
        });

        contentView.setWebViewClient(new WebViewClient() {

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest req){
                if(adBlockEnabled){
                    Uri u = req.getUrl();
                    if(u != null && isAdHost(u.getHost())){
                        blockedCount++;
                        if(blockedCount % 5 == 0){
                            callShellStr("shellSetBlocked", String.valueOf(blockedCount));
                        }
                        return new WebResourceResponse(
                            "text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                    }
                }
                return null;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r){
                Uri uri = r.getUrl();
                String scheme = uri.getScheme();
                if(!"http".equals(scheme) && !"https".equals(scheme) && !"file".equals(scheme)){
                    try {
                        startActivity(new Intent(Intent.ACTION_VIEW, uri));
                        return true;
                    } catch(Exception e){ return false; }
                }
                return false;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap f){
                if(url == null) return;
                lastUrl = url;
                if(url.startsWith("file://") && url.contains("home.html")){
                    callShellStr("shellSetUrl", "");
                } else if(!url.startsWith("file://")){
                    callShellStr("shellSetUrl", url);
                }
                updateNav();
            }

            @Override
            public void onPageCommitVisible(WebView v, String url){
                injectDesktopViewport(v);
            }

            @Override
            public void onPageFinished(WebView v, String url){
                updateNav();
                if(adBlockEnabled) injectAdCss(v);
                injectNotificationShim(v);
                if(desktopMode) injectDesktopViewport(v);
            }

            @Override
            public void onReceivedError(WebView v, WebResourceRequest r, WebResourceError e){
                if(r.isForMainFrame()){
                    String msg = (Build.VERSION.SDK_INT >= 23)
                        ? e.getDescription().toString() : "load failed";
                    Toast.makeText(MainActivity.this, "Failed: " + msg, Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    private void injectAdCss(WebView v){
        String css = ".adsbygoogle,[class*='advert'],[id*='advert']," +
            "[class*='sponsored'],[id*='sponsored'],.ad-slot,.ad-banner," +
            "[class*='taboola'],[class*='outbrain']{display:none!important}";
        String js = "(function(){if(document.getElementById('b88-ad'))return;" +
            "var s=document.createElement('style');s.id='b88-ad';" +
            "s.textContent=" + jsStr(css) + ";" +
            "document.documentElement.appendChild(s);})();";
        v.evaluateJavascript(js, null);
    }

    /* Replaces window.Notification with a bridge so pages can raise real
       Android notifications without needing an HTTPS origin. */
    private void injectNotificationShim(WebView v){
        String js = "(function(){if(window.__b88Notif)return;window.__b88Notif=1;" +
            "try{var N=function(t,o){try{AndroidBridge.notify(String(t)," +
            "String(o&&o.body?o.body:''));}catch(e){}};" +
            "N.permission='granted';" +
            "N.requestPermission=function(cb){try{if(cb)cb('granted');}catch(e){}" +
            "return Promise.resolve('granted');};" +
            "Object.defineProperty(window,'Notification'," +
            "{value:N,configurable:true,writable:true});" +
            "}catch(e){}})();";
        v.evaluateJavascript(js, null);
    }

    private void updateNav(){
        callShellTwo("shellSetNav",
            String.valueOf(contentView.canGoBack()),
            String.valueOf(contentView.canGoForward()));
    }

    /* ---- call into the shell webview ---- */
    private void callShellStr(String fn, String arg){
        callShellRaw(fn, jsStr(arg));
    }
    private void callShellBool(String fn, boolean arg){
        callShellRaw(fn, String.valueOf(arg));
    }
    private void callShellTwo(String fn, String a, String b){
        callShellRaw(fn, a + "," + b);
    }
    private void callShellRaw(final String fn, final String args){
        runOnUiThread(new Runnable(){
            @Override public void run(){
                try {
                    shellView.evaluateJavascript("window." + fn + "(" + args + ");", null);
                } catch(Exception ignored){}
            }
        });
    }
    private String jsStr(String s){
        if(s == null) return "null";
        return "\"" + s.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "") + "\"";
    }

    /* ---- bridge: what the HTML can call ---- */
    public class Bridge {

        @JavascriptInterface
        public void navigate(final String url){
            runOnUiThread(new Runnable(){
                @Override public void run(){
                    if(url == null || url.isEmpty()) return;
                    String target = url;
                    if(!url.startsWith("http") && !url.startsWith("file")
                            && !url.startsWith("about")){
                        target = "https://duckduckgo.com/?q=" + Uri.encode(url);
                    }
                    contentView.loadUrl(target);
                }
            });
        }

        @JavascriptInterface
        public void goHome(){
            runOnUiThread(new Runnable(){
                @Override public void run(){
                    contentView.loadUrl("file:///android_asset/home.html");
                }
            });
        }

        @JavascriptInterface
        public void goBack(){
            runOnUiThread(new Runnable(){
                @Override public void run(){
                    if(contentView.canGoBack()) contentView.goBack();
                }
            });
        }

        @JavascriptInterface
        public void goForward(){
            runOnUiThread(new Runnable(){
                @Override public void run(){
                    if(contentView.canGoForward()) contentView.goForward();
                }
            });
        }

        @JavascriptInterface
        public void reload(){
            runOnUiThread(new Runnable(){
                @Override public void run(){ contentView.reload(); }
            });
        }

        @JavascriptInterface
        public void setSheetOpen(final boolean open){
            sheetOpen = open;
        }

        @JavascriptInterface
        public void setAdblock(final boolean on){
            adBlockEnabled = on;
            runOnUiThread(new Runnable(){
                @Override public void run(){
                    if(on) injectAdCss(contentView);
                    else contentView.evaluateJavascript(
                        "(function(){var e=document.getElementById('b88-ad');" +
                        "if(e)e.remove();})();", null);
                }
            });
        }

        @JavascriptInterface
        public void setDesktopMode(final boolean on){
            runOnUiThread(new Runnable(){
                @Override public void run(){ applyDesktopMode(on); }
            });
        }

        @JavascriptInterface
        public void share(final String url){
            runOnUiThread(new Runnable(){
                @Override public void run(){
                    if(url == null || url.isEmpty()) return;
                    Intent i = new Intent(Intent.ACTION_SEND);
                    i.setType("text/plain");
                    i.putExtra(Intent.EXTRA_TEXT, url);
                    startActivity(Intent.createChooser(i, "Share"));
                }
            });
        }

        @JavascriptInterface
        public void clearAllData(){
            runOnUiThread(new Runnable(){
                @Override public void run(){
                    CookieManager.getInstance().removeAllCookies(null);
                    CookieManager.getInstance().flush();
                    contentView.clearCache(true);
                    contentView.clearHistory();
                    contentView.clearFormData();
                    try {
                        SharedPreferences sp = getSharedPreferences("b88.history", MODE_PRIVATE);
                        sp.edit().clear().apply();
                    } catch(Exception ignored){}
                    Toast.makeText(MainActivity.this, "Browsing data cleared",
                        Toast.LENGTH_SHORT).show();
                }
            });
        }

        @JavascriptInterface
        public void addPin(final String name, final String url){
            runOnUiThread(new Runnable(){
                @Override public void run(){
                    try {
                        SharedPreferences sp = getSharedPreferences("b88.pins", MODE_PRIVATE);
                        JSONArray arr = new JSONArray(sp.getString("list", "[]"));
                        JSONArray next = new JSONArray();
                        JSONObject item = new JSONObject();
                        item.put("name", name != null ? name : "");
                        item.put("url",  url  != null ? url  : "");
                        next.put(item);
                        for(int i = 0; i < arr.length() && i < 20; i++){
                            JSONObject old = arr.optJSONObject(i);
                            if(old == null) continue;
                            if(!url.equals(old.optString("url", ""))) next.put(old);
                        }
                        sp.edit().putString("list", next.toString()).apply();

                        String cur = contentView.getUrl();
                        if(cur != null && cur.contains("home.html")) contentView.reload();
                    } catch(Exception e){
                        Toast.makeText(MainActivity.this, "Pin failed",
                            Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }

        @JavascriptInterface
        public String getPins(){
            try {
                SharedPreferences sp = getSharedPreferences("b88.pins", MODE_PRIVATE);
                return sp.getString("list", "[]");
            } catch(Exception e){ return "[]"; }
        }

        @JavascriptInterface
        public void notify(final String title, final String body){
            runOnUiThread(new Runnable(){
                @Override public void run(){ postNotification(title, body); }
            });
        }

        @JavascriptInterface
        public void saveAPK(final String b64, final String filename){
            runOnUiThread(new Runnable(){
                @Override public void run(){
                    try {
                        byte[] bytes = Base64.decode(b64, Base64.DEFAULT);
                        File dir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                        if(!dir.exists()) dir.mkdirs();
                        File out = new File(dir, filename);
                        FileOutputStream fos = new FileOutputStream(out);
                        fos.write(bytes);
                        fos.close();
                        Toast.makeText(MainActivity.this, "Saved: " + filename,
                            Toast.LENGTH_LONG).show();
                    } catch(Exception e){
                        Toast.makeText(MainActivity.this, "Save failed",
                            Toast.LENGTH_SHORT).show();
                    }
                }
            });
        }
    }

    /* ---- runtime permissions ---- */
    private void requestPerms(){
        List<String> need = new ArrayList<>();
        if(Build.VERSION.SDK_INT >= 33){
            if(!has(Manifest.permission.POST_NOTIFICATIONS))
                need.add(Manifest.permission.POST_NOTIFICATIONS);
            if(!has("android.permission.READ_MEDIA_IMAGES"))
                need.add("android.permission.READ_MEDIA_IMAGES");
        } else if(Build.VERSION.SDK_INT >= 23){
            if(!has(Manifest.permission.READ_EXTERNAL_STORAGE))
                need.add(Manifest.permission.READ_EXTERNAL_STORAGE);
        }
        if(Build.VERSION.SDK_INT >= 23 && !has(Manifest.permission.CAMERA)){
            need.add(Manifest.permission.CAMERA);
        }
        if(!need.isEmpty()){
            requestPermissions(need.toArray(new String[0]), REQ_PERMS);
        }
    }

    private boolean has(String p){
        return checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED;
    }

    /* ---- lifecycle ---- */
    @Override
    protected void onActivityResult(int req, int res, Intent data){
        if(req == REQ_FILE){
            if(filePathCallback != null){
                Uri[] results = null;
                if(res == Activity.RESULT_OK && data != null){
                    if(data.getClipData() != null){
                        int n = data.getClipData().getItemCount();
                        results = new Uri[n];
                        for(int i = 0; i < n; i++)
                            results[i] = data.getClipData().getItemAt(i).getUri();
                    } else if(data.getData() != null){
                        results = new Uri[]{ data.getData() };
                    }
                }
                filePathCallback.onReceiveValue(results);
                filePathCallback = null;
            }
            return;
        }
        super.onActivityResult(req, res, data);
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event){
        if(keyCode == KeyEvent.KEYCODE_BACK){
            if(sheetOpen){
                shellView.evaluateJavascript(
                    "(function(){var s=document.getElementById('scrim');" +
                    "if(s)s.click();})();", null);
                return true;
            }
            String url = contentView.getUrl();
            if(url != null && !url.contains("home.html")){
                if(contentView.canGoBack()) contentView.goBack();
                else contentView.loadUrl("file:///android_asset/home.html");
                return true;
            }
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override protected void onPause(){
        super.onPause();
        if(contentView != null) contentView.onPause();
        if(shellView != null) shellView.onPause();
    }
    @Override protected void onResume(){
        super.onResume();
        if(contentView != null) contentView.onResume();
        if(shellView != null) shellView.onResume();
    }
    @Override protected void onDestroy(){
        if(contentView != null){ contentView.destroy(); contentView = null; }
        if(shellView != null){ shellView.destroy(); shellView = null; }
        super.onDestroy();
    }
              }
