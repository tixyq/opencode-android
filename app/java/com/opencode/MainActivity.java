package com.opencode;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

public class MainActivity extends Activity {
    private static final String TAG = "OpenCode";

    // Test ports: APK runs its own server pair so it never collides with
    // anything else on the device.
    // Release: 8080 / 49374.
    private static final String UI_ORIGIN = "http://127.0.0.1:18080";
    private static final String HOME_URL = UI_ORIGIN + "/";
    private static final String LOADER_URL = UI_ORIGIN + "/loader.html";
    private static final String ASSET_LOADER_URL = "file:///android_asset/loader.html";
    // Server address is fixed; the password lives in the app-owned
    // service.json and never changes (survives restarts).
    private static final String SERVER_URL = "http://127.0.0.1:49474";

    private static final String PREFS = "oc";
    private static final String KEY_SEEDED = "seeded_v3";

    private static final int REQUEST_STORAGE = 41;
    private static final int REQUEST_FILE = 43;

    private WebView web;
    private boolean seeding;
    private boolean waiting;
    private boolean destroyed;
    private int reseeds;
    private android.webkit.ValueCallback<android.net.Uri[]> fileCallback;

    /** True while the chat UI is visible: notifier stays silent then. */
    public static volatile boolean foreground;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Foreground service first: owns the OpenCode server process.
        Intent svc = new Intent(this, ServerService.class);
        startForegroundService(svc);

        // Storage access: asked once, never nagged, never redirected.
        // Without it only /sdcard features degrade; the app home works.
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            boolean asked = getSharedPreferences(PREFS, MODE_PRIVATE)
                    .getBoolean("storage_requested", false);
            String read = android.Manifest.permission.READ_EXTERNAL_STORAGE;
            String write = android.Manifest.permission.WRITE_EXTERNAL_STORAGE;
            boolean granted = checkSelfPermission(read)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED
                    && checkSelfPermission(write)
                    == android.content.pm.PackageManager.PERMISSION_GRANTED;
            if (!asked && !granted) {
                getSharedPreferences(PREFS, MODE_PRIVATE)
                        .edit().putBoolean("storage_requested", true).apply();
                try {
                    requestPermissions(new String[]{read, write}, REQUEST_STORAGE);
                } catch (Exception ignored) {
                }
            }
        }

        FrameLayout root = new FrameLayout(this);
        web = new WebView(this);
        WebSettings settings = web.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setLoadWithOverviewMode(true);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);
        settings.setMediaPlaybackRequiresUserGesture(false);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                onPageReady(view, url);
            }
        });
        web.setWebChromeClient(new android.webkit.WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view,
                    android.webkit.ValueCallback<android.net.Uri[]> callback,
                    android.webkit.WebChromeClient.FileChooserParams params) {
                if (destroyed) {
                    return false;
                }
                if (fileCallback != null) {
                    try {
                        fileCallback.onReceiveValue(null);
                    } catch (Exception ignored) {
                    }
                }
                fileCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    if (params.getMode()
                            == android.webkit.WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    }
                    startActivityForResult(Intent.createChooser(intent,
                            getString(R.string.app_name)), REQUEST_FILE);
                    return true;
                } catch (Exception e) {
                    Log.i(TAG, "file chooser failed: " + e);
                    fileCallback = null;
                    try {
                        callback.onReceiveValue(null);
                    } catch (Exception ignored) {
                    }
                    return false;
                }
            }
        });
        root.addView(web, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        setContentView(root);

        // Always a fresh boot through the readiness gate: restored pages
        // can point at a server that is still (re)starting.
        Log.i(TAG, "loading asset loader");
        web.loadUrl(ASSET_LOADER_URL);
    }

    private void onPageReady(WebView view, String url) {
        if (url != null && url.startsWith("file://")) {
            onAssetLoaderReady(view);
            return;
        }
        if (url != null && url.contains("/loader.html")) {
            onHttpLoaderReady(view);
            return;
        }
        onHomeReady(view);
    }

    /**
     * Makes sure the starter project exists server-side. Creating a session
     * with a directory implicitly registers its project, which is what the
     * home screen lists and what new sessions attach to.
     */
    /**
     * Makes sure the app home directory is a known project (with id), so
     * sessions can be created with no user folders involved. Creates no
     * sessions and no chats: only the project entry, once.
     */
    private void ensureHomeProject() {
        String directory = new java.io.File(getFilesDir(), "home").getAbsolutePath();
        String password = readPassword();
        if (password == null) {
            return;
        }
        try {
            String projects = apiGet("/api/project", password);
            if (projects != null && projects.contains(directory)) {
                Log.i(TAG, "home project exists");
                return;
            }
            String encoded = java.net.URLEncoder.encode(directory, "UTF-8");
            String location = apiGet(
                    "/api/location?location%5Bdirectory%5D=" + encoded, password);
            Log.i(TAG, "home project materialized: " + (location != null));
        } catch (Exception e) {
            Log.i(TAG, "ensure home project failed: " + e);
        }
    }

    private String basicAuth(String password) {        String raw = "opencode:" + password;
        try {
            byte[] bytes = raw.getBytes("UTF-8");
            return "Basic " + android.util.Base64.encodeToString(
                    bytes, android.util.Base64.NO_WRAP);
        } catch (java.io.UnsupportedEncodingException e) {
            return "";
        }
    }

    private String apiGet(String path, String password) {
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL url = new java.net.URL(SERVER_URL + path);
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", basicAuth(password));
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) {
                return null;
            }
            java.io.InputStream in = connection.getInputStream();
            try {
                byte[] buffer = new byte[16384];
                StringBuilder out = new StringBuilder();
                int n;
                while ((n = in.read(buffer)) >= 0) {
                    out.append(new String(buffer, 0, n, "UTF-8"));
                }
                return out.toString();
            } finally {
                in.close();
            }
        } catch (java.io.IOException e) {
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Writes a status line into the loader page. Visible without logcat. */
    private void report(WebView view, final String text) {
        Log.i(TAG, "report: " + text);
        final String safe = text.replace("\\", "\\\\").replace("'", "\\'");
        view.evaluateJavascript(
                "document.getElementById('dbg')"
                        + "&&(document.getElementById('dbg').textContent='" + safe + "')",
                null);
    }

    /**
     * Blocks (background thread) until the UI files are extracted and the
     * API answers. Any HTTP status counts: even 401 proves the server is up.
     * No timeout: the loader stays until everything is really up.
     */
    private void waitUntilReady() {
        while (!destroyed) {
            boolean www = new java.io.File(getFilesDir(), "www/index.html").isFile();
            boolean api = apiAnswers();
            if (www && api) {
                return;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private boolean apiAnswers() {
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL url = new java.net.URL(SERVER_URL + "/api/info");
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setConnectTimeout(2000);
            connection.setReadTimeout(2000);
            connection.setRequestMethod("GET");
            int code = connection.getResponseCode();
            return code > 0;
        } catch (java.io.IOException e) {
            return false;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** Asset loader (instant, needs no server): wait until everything
        is up, then move to the served loader on the UI origin. */
    private void onAssetLoaderReady(final WebView view) {
        if (waiting) {
            return;
        }
        waiting = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                waitUntilReady();
                if (destroyed) {
                    return;
                }
                // Always: the app home directory is the default project, so
                // sessions can be created with no user folders involved.
                // Idempotent and cheap (single GET when present).
                ensureHomeProject();
                if (destroyed) {
                    return;
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        waiting = false;
                        if (destroyed) {
                            return;
                        }
                        if (getSharedPreferences(PREFS, MODE_PRIVATE)
                                .getBoolean(KEY_SEEDED, false)) {
                            view.loadUrl(HOME_URL);
                        } else {
                            view.loadUrl(LOADER_URL);
                        }
                    }
                });
            }
        }, "oc-ready").start();
    }

    /** Served loader (UI origin): plant the credentials, then open home. */
    private void onHttpLoaderReady(final WebView view) {
        // Additive self-heal: make sure the home project entry exists in
        // the stored state (older seeds lack it). Never removes anything.
        final String home =
                new java.io.File(getFilesDir(), "home").getAbsolutePath();
        String merge = "(function(){try{"
                + "var K='opencode.global.dat:server';"
                + "var s=localStorage.getItem(K);if(!s)return 'none';"
                + "var o=JSON.parse(s);o.projects=o.projects||{};"
                + "o.projects.local=o.projects.local||[];"
                + "var H='" + home.replace("\\", "\\\\").replace("'", "\\'") + "';"
                + "var has=o.projects.local.some(function(p){return p&&(p.worktree===H);});"
                + "if(!has){o.projects.local.push({worktree:H,expanded:true});}"
                + "o.lastProject=o.lastProject||{};o.lastProject.local=H;"
                + "localStorage.setItem(K,JSON.stringify(o));"
                + "return has?'had':'added';}catch(e){return 'bad:'+e;}})()";
        view.evaluateJavascript(merge,
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        Log.i(TAG, "merge result: " + value);
                        if (destroyed) {
                            return;
                        }
                        if (getSharedPreferences(PREFS, MODE_PRIVATE)
                                .getBoolean(KEY_SEEDED, false)) {
                            view.loadUrl(HOME_URL);
                            return;
                        }
                        if (seeding) {
                            return;
                        }
                        seedAndOpen(view);
                    }
                });
    }

    private void seedAndOpen(final WebView view) {
        final String password = readPassword();
        if (password == null) {
            // Credentials file missing: stay on the loader, retry shortly.
            view.postDelayed(new Runnable() {
                @Override
                public void run() {
                    view.reload();
                }
            }, 3000);
            return;
        }
        seeding = true;
        view.evaluateJavascript(seedJs(password, new java.io.File(getFilesDir(), "home").getAbsolutePath()),
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        seeding = false;
                        Log.i(TAG, "seed result: " + value);
                        view.loadUrl(HOME_URL);
                    }
                });
    }

    private void onHomeReady(final WebView view) {
        // Always verify, even when seeded: the server password may have
        // rotated (config recreated) while the stored entry went stale.
        // A stale entry means every API call 401s: loader looks fine,
        // the whole UI is dead. Re-seed instead of trusting the flag.
        if (seeding) {
            return;
        }
        seeding = true;
        // Authoritative check, done natively: does the stored entry really
        // connect? Storage presence alone is not enough (stale password).
        new Thread(new Runnable() {
            @Override
            public void run() {
                final String password = readPassword();
                final boolean connected =
                        password != null && apiGet("/api/info", password) != null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        seeding = false;
                        if (destroyed) {
                            return;
                        }
                        Log.i(TAG, "connectivity: " + connected);
                        if (connected) {
                            getSharedPreferences(PREFS, MODE_PRIVATE)
                                    .edit().putBoolean(KEY_SEEDED, true).apply();
                            return;
                        }
                        // Entry missing or stale: plant the current password
                        // and reload. Park on the loader after a few tries so
                        // the connect screen is never shown.
                        if (reseeds < 5 && password != null) {
                            reseeds++;
                            seedAndReload(view);
                            return;
                        }
                        view.loadUrl(LOADER_URL);
                    }
                });
            }
        }, "oc-verify").start();
    }

    private void seedAndReload(final WebView view) {
        seeding = true;
        final String password = readPassword();
        if (password == null) {
            seeding = false;
            return;
        }
        view.evaluateJavascript(seedJs(password, new java.io.File(getFilesDir(), "home").getAbsolutePath()),
                new android.webkit.ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String value) {
                        seeding = false;
                        view.reload();
                    }
                });
    }

    private String seedJs(String password, String home) {
        String state = "{\"list\":[{\"type\":\"http\",\"http\":{\"url\":\""
                + SERVER_URL + "\",\"password\":\"" + escapeJson(password)
                + "\"}}],\"hidden\":{},"
                + "\"projects\":{\"local\":[{\"worktree\":\"" + escapeJson(home)
                + "\",\"expanded\":true}]},"
                + "\"lastProject\":{\"local\":\"" + escapeJson(home) + "\"},"
                + "\"recentlyClosed\":{}}";
        return "(function(){try{"
                + "localStorage.setItem('opencode.global.dat:server','"
                + state.replace("\\", "\\\\").replace("'", "\\'") + "');"
                + "localStorage.setItem('opencode.settings.dat:defaultServerUrl','"
                + SERVER_URL + "');"
                + "var s=localStorage.getItem('opencode.global.dat:server');"
                + "return 'ok:'+((s&&s.length)||0);}catch(e){return 'fail:'+e;}})()";
    }

    private String readPassword() {
        java.io.File file = new java.io.File(
                new java.io.File(getFilesDir(), "home/.config/opencode"), "service.json");
        if (!file.isFile()) {
            return null;
        }
        try {
            java.io.FileInputStream fis = new java.io.FileInputStream(file);
            try {
                byte[] buffer = new byte[(int) Math.min(file.length(), 1024)];
                int n = fis.read(buffer);
                if (n <= 0) {
                    return null;
                }
                String json = new String(buffer, 0, n, "UTF-8");
                org.json.JSONObject object = new org.json.JSONObject(json);
                String password = object.optString("password", "");
                return password.length() == 0 ? null : password;
            } finally {
                fis.close();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeJson(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    protected void onResume() {
        super.onResume();
        foreground = true;
    }

    @Override
    protected void onPause() {
        foreground = false;
        super.onPause();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE || fileCallback == null) {
            return;
        }
        android.webkit.ValueCallback<android.net.Uri[]> callback = fileCallback;
        fileCallback = null;
        try {
            if (resultCode != RESULT_OK || data == null) {
                callback.onReceiveValue(null);
                return;
            }
            java.util.ArrayList<android.net.Uri> uris =
                    new java.util.ArrayList<android.net.Uri>();
            if (data.getClipData() != null) {
                int n = data.getClipData().getItemCount();
                for (int i = 0; i < n; i++) {
                    android.net.Uri uri = data.getClipData().getItemAt(i).getUri();
                    if (uri != null) {
                        try {
                            getContentResolver().takePersistableUriPermission(uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        } catch (Exception ignored) {
                        }
                        uris.add(uri);
                    }
                }
            } else if (data.getData() != null) {
                try {
                    getContentResolver().takePersistableUriPermission(data.getData(),
                            Intent.FLAG_GRANT_READ_URI_PERMISSION);
                } catch (Exception ignored) {
                }
                uris.add(data.getData());
            }
            callback.onReceiveValue(uris.toArray(new android.net.Uri[0]));
        } catch (Exception e) {
            try {
                callback.onReceiveValue(null);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (web != null) {
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
