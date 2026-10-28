package org.keeperrecomped.launcher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Insets;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.Display;
import android.view.WindowInsets;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONObject;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The launcher: a WebView page (assets/launcher) with the Play, Workshop, News,
 * Graphics, Gameplay, Controls, Advanced and Credits tabs, talking to Java through
 * the "KR" bridge below. Long work runs in {@link PrepareService}.
 */
public final class LauncherActivity extends Activity {
    private static final int PICK_FOLDER = 1;
    static final String TAG = Prepare.TAG;

    private WebView web;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private SharedPreferences prefs;
    private Settings settings;
    private boolean pageReady;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("launcher", MODE_PRIVATE);
        settings = new Settings(this);
        if (isDebuggable() && getIntent().getBooleanExtra("debug_show_when_locked", false)) {
            // Engineering builds only: show this activity above the keyguard for
            // unattended adb testing. It does not unlock the device.
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        }
        if (isDebuggable() && getIntent().hasExtra("debug_relay")) {
            String relay = getIntent().getStringExtra("debug_relay");
            prefs.edit().putString("debug_relay", relay == null || relay.isEmpty() ? null : relay).apply();
        }
        Net.configure(this);
        AppLog.init(this);
        getWindow().setStatusBarColor(Color.TRANSPARENT);
        getWindow().setNavigationBarColor(Color.TRANSPARENT);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0E0907);
        web = new WebView(this);
        web.setBackgroundColor(0xFF0E0907);
        root.addView(web, new FrameLayout.LayoutParams(-1, -1));
        // Android 15 draws edge to edge: keep the page clear of the system bars and cutout.
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsets.CONSUMED;
        });
        setContentView(root);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setMediaPlaybackRequiresUserGesture(true);
        ws.setTextZoom(100);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                openExternal(request.getUrl().toString());
                return true; // the launcher page never navigates away
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                pageReady = true;
                send(PrepareService.lastProgress().length() > 0 ? PrepareService.lastProgress() : PrepareService.idle(), "progress");
                checkForUpdate();
                debugAction(getIntent());
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage m) {
                AppLog.i("js: " + m.message() + " (" + m.sourceId() + ":" + m.lineNumber() + ")");
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "KR");
        web.loadUrl("file:///android_asset/launcher/index.html");

        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                () -> web.evaluateJavascript("window.kr && kr.back()", value -> {
                    if (!"true".equals(value))
                        finish();
                }));
        PrepareService.observe(p -> send(p, "progress"));
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (pageReady)
            debugAction(intent);
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /**
     * Engineering builds: drive the page from adb, e.g.
     * am start -n org.keeperrecomped.app/org.keeperrecomped.launcher.LauncherActivity --es debug_action tab:news
     * Actions: tab:NAME, prepare, update, play:keeperfx, play:original, cancel.
     */
    private void debugAction(Intent intent) {
        if (intent != null && isDebuggable() && intent.getBooleanExtra("debug_tree", false) && intent.getData() != null) {
            // am start ... --ez debug_tree true --grant-read-uri-permission -d content://.../tree/...
            prefs.edit().putString("tree", intent.getData().toString()).putBoolean("tree_new", true).apply();
            intent.removeExtra("debug_tree");
            send(stateJson(), "state");
        }
        String action = intent == null ? null : intent.getStringExtra("debug_action");
        if (action == null || !isDebuggable())
            return;
        intent.removeExtra("debug_action");
        AppLog.i("debug action " + action);
        String js;
        if (action.startsWith("tab:"))
            js = "showTab(" + JSONObject.quote(action.substring(4)) + ")";
        else if (action.equals("prepare"))
            js = "document.getElementById('prepare').click()";
        else if (action.equals("update"))
            js = "document.getElementById('updateNow').click()";
        else if (action.equals("cancel"))
            js = "KR.cancel()";
        else if (action.startsWith("play:"))
            js = "document.getElementById(" + JSONObject.quote(action.endsWith("original") ? "playOriginal" : "playKfx") + ").click()";
        else
            return;
        ui.post(() -> web.evaluateJavascript(js, null));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (pageReady)
            send(stateJson(), "state");
    }

    @Override
    protected void onDestroy() {
        PrepareService.observe(null);
        io.shutdownNow();
        web.destroy();
        super.onDestroy();
    }

    /** Pushes an event to the page: kr.onEvent(type, json). */
    void send(JSONObject payload, String type) {
        String js = "window.kr && kr.onEvent(" + JSONObject.quote(type) + "," + payload + ")";
        ui.post(() -> web.evaluateJavascript(js, null));
    }

    private void openExternal(String url) {
        if (url == null || !(url.startsWith("https://") || url.startsWith("http://")))
            return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (Exception e) {
            AppLog.w("no browser for " + url);
        }
    }

    private void checkForUpdate() {
        if (!settings.bool("check_updates") || !settings.bool("website_integration"))
            return;
        JSONObject st = State.load(this);
        if (!st.optBoolean("prepared"))
            return;
        long interval = settings.integer("update_interval_days") * 86400000L;
        long lastCheck = prefs.getLong("last_update_check", 0);
        if (interval > 0 && System.currentTimeMillis() - lastCheck < interval)
            return;
        io.execute(() -> {
            try {
                KfxApi api = new KfxApi(this);
                JSONObject state = State.load(this);
                String found = null, title = "";
                String installed = state.optString("kfx_version");
                if (!state.optBoolean("kfx_from_folder")) {
                    JSONObject stable = api.stable();
                    if (!stable.optString("version").equals(installed))
                        found = stable.optString("name", "KeeperFX " + stable.optString("version"));
                }
                if (found == null && "ALPHA".equals(settings.get("release_channel"))) {
                    JSONObject alpha = api.alpha();
                    if (!alpha.optString("version").equals(state.optString("alpha_version"))) {
                        found = "Alpha patch " + alpha.optString("version");
                        title = alpha.optString("workflow_title");
                    }
                }
                prefs.edit().putLong("last_update_check", System.currentTimeMillis()).apply();
                if (found != null) {
                    send(new JSONObject().put("version", found).put("title", title), "update");
                    if (settings.bool("auto_update") && !PrepareService.isRunning())
                        PrepareService.start(this, new Intent().putExtra(PrepareService.EXTRA_TASK, "update"));
                }
            } catch (Exception e) {
                AppLog.i("update check: " + e.getMessage());
            }
        });
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != PICK_FOLDER || result != RESULT_OK || data == null || data.getData() == null)
            return;
        Uri tree = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (SecurityException e) {
            AppLog.w("no persistable permission: " + e);
        }
        prefs.edit().putString("tree", tree.toString()).putBoolean("tree_new", true).apply();
        send(stateJson(), "state");
    }

    static String describeTree(String uri) {
        if (uri == null)
            return "";
        try {
            String id = DocumentsContract.getTreeDocumentId(Uri.parse(uri));
            int colon = id.indexOf(':');
            String volume = colon > 0 ? id.substring(0, colon) : "";
            String path = colon >= 0 ? id.substring(colon + 1) : id;
            return ("primary".equals(volume) ? "Internal storage" : volume) + (path.isEmpty() ? "" : "/" + path);
        } catch (Exception e) {
            return uri;
        }
    }

    JSONObject stateJson() {
        try {
            JSONObject s = State.load(this);
            JSONObject o = new JSONObject();
            o.put("state", s);
            o.put("folder", describeTree(prefs.getString("tree", null)));
            o.put("folder_new", prefs.getBoolean("tree_new", false));
            o.put("engine", GameLauncher.engineInstalled(this));
            try (java.io.InputStream in = getAssets().open("engine.json")) {
                o.put("engine_info", new JSONObject(new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
            } catch (Exception ignored) {
            }
            o.put("original_build", GameLauncher.originalBuild(this) != null);
            o.put("build_hook_present", classExists(Prepare.BUILD_HOOK));
            o.put("kfx_ready", new File(Paths.kfx(this), "fxdata").isDirectory() && new File(Paths.kfx(this), "data/bluepal.dat").isFile());
            o.put("running", PrepareService.isRunning());
            o.put("channel", settings.get("release_channel"));
            o.put("website", settings.bool("website_integration"));
            o.put("app_version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
            Display d = getDisplay();
            float max = 0;
            for (Display.Mode m : d.getSupportedModes())
                max = Math.max(max, m.getRefreshRate());
            o.put("display", d.getMode().getPhysicalWidth() + "x" + d.getMode().getPhysicalHeight() + " @ " + Math.round(max) + " Hz");
            o.put("device", Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /** Methods the page calls as KR.name(...). Called on a WebView binder thread. */
    final class Bridge {
        @JavascriptInterface
        public String state() { return stateJson().toString(); }

        @JavascriptInterface
        public String schema() {
            try {
                return settings.schema().toString();
            } catch (Exception e) {
                return "{\"settings\":[]}";
            }
        }

        /** @return "" when stored, else the reason */
        @JavascriptInterface
        public String set(String key, String value) {
            String err = settings.set(key, value);
            return err == null ? "" : err;
        }

        @JavascriptInterface
        public void resetSettings() { settings.resetAll(); }

        @JavascriptInterface
        public void pickFolder() {
            ui.post(() -> {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
                try {
                    startActivityForResult(i, PICK_FOLDER);
                } catch (Exception e) {
                    send(err("No folder picker is available on this device."), "toast");
                }
            });
        }

        @JavascriptInterface
        public String prepare() {
            if (PrepareService.isRunning())
                return "A task is already running.";
            String tree = prefs.getString("tree", null);
            boolean fresh = prefs.getBoolean("tree_new", false);
            boolean imported = State.load(LauncherActivity.this).has("imported_at");
            if (tree == null && !imported)
                return "Choose your Dungeon Keeper folder first.";
            askNotifications();
            Intent i = new Intent().putExtra(PrepareService.EXTRA_TASK, "prepare");
            if (tree != null && (fresh || !imported))
                i.putExtra(PrepareService.EXTRA_TREE, tree);
            prefs.edit().putBoolean("tree_new", false).apply();
            PrepareService.start(LauncherActivity.this, i);
            return "";
        }

        @JavascriptInterface
        public String update() {
            if (PrepareService.isRunning())
                return "A task is already running.";
            PrepareService.start(LauncherActivity.this, new Intent().putExtra(PrepareService.EXTRA_TASK, "update"));
            return "";
        }

        @JavascriptInterface
        public void cancel() {
            startService(new Intent(LauncherActivity.this, PrepareService.class).setAction(PrepareService.ACTION_CANCEL));
        }

        @JavascriptInterface
        public String installWorkshop(String id, String name, String url, String file, String size) {
            if (PrepareService.isRunning())
                return "A task is already running.";
            if (url == null || !url.startsWith("https://keeperfx.net/"))
                return "Unexpected download address.";
            long bytes = -1;
            try {
                bytes = Long.parseLong(size);
            } catch (NumberFormatException ignored) {
            }
            PrepareService.start(LauncherActivity.this, new Intent()
                    .putExtra(PrepareService.EXTRA_TASK, "workshop").putExtra(PrepareService.EXTRA_WS_ID, id)
                    .putExtra(PrepareService.EXTRA_WS_NAME, name).putExtra(PrepareService.EXTRA_WS_URL, url)
                    .putExtra(PrepareService.EXTRA_WS_FILE, file).putExtra(PrepareService.EXTRA_WS_SIZE, bytes));
            return "";
        }

        /** @return "" when the game started, else why not */
        @JavascriptInterface
        public String play(String profile) {
            android.os.Bundle debug = null;
            if (isDebuggable()) {
                // Pass the engine's own debug switches through (see GameActivity).
                debug = new android.os.Bundle();
                for (String key : new String[]{"debug_show_when_locked", "input_diagnostics", "enable_touch_probe"})
                    if (getIntent().getBooleanExtra(key, false))
                        debug.putBoolean(key, true);
            }
            String e = GameLauncher.play(LauncherActivity.this, profile, debug);
            return e == null ? "" : e;
        }

        /** Async keeperfx.net GET; answers with kr.onEvent("api", {id, ok, body|error}). */
        @JavascriptInterface
        public void api(String endpoint, String id) {
            if (!endpoint.matches("[a-z0-9/._-]+")) {
                send(apiResult(id, null, "bad endpoint"), "api");
                return;
            }
            io.execute(() -> {
                try {
                    send(apiResult(id, new KfxApi(LauncherActivity.this).get(endpoint), null), "api");
                } catch (Exception e) {
                    send(apiResult(id, null, e.getMessage()), "api");
                }
            });
        }

        /** Async: kr.onEvent("api", {id, ok, body: {files: [...]}}) for a workshop item. */
        @JavascriptInterface
        public void workshopFiles(String itemId, String pageUrl, String id) {
            if (!itemId.matches("[0-9]+")) {
                send(apiResult(id, null, "bad item"), "api");
                return;
            }
            io.execute(() -> {
                try {
                    org.json.JSONArray files = new KfxApi(LauncherActivity.this).workshopFiles(itemId, pageUrl);
                    send(apiResult(id, new JSONObject().put("files", files).toString(), null), "api");
                } catch (Exception e) {
                    send(apiResult(id, null, e.getMessage()), "api");
                }
            });
        }

        @JavascriptInterface
        public void open(String url) { ui.post(() -> openExternal(url)); }

        /** Text of a bundled licence (assets/licenses/name), for the Credits tab. */
        @JavascriptInterface
        public String license(String name) {
            if (!name.matches("[A-Za-z0-9._-]+"))
                return "";
            try (java.io.InputStream in = getAssets().open("licenses/" + name)) {
                return new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            } catch (Exception e) {
                return "";
            }
        }

        @JavascriptInterface
        public String licenses() {
            try {
                String[] names = getAssets().list("licenses");
                return new org.json.JSONArray(names == null ? new String[0] : names).toString();
            } catch (Exception e) {
                return "[]";
            }
        }

        @JavascriptInterface
        public void log(String message) { AppLog.i("page: " + message); }

        @JavascriptInterface
        public void exit() { ui.post(LauncherActivity.this::finish); }
    }

    private static JSONObject apiResult(String id, String body, String error) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id).put("ok", body != null);
            if (body != null)
                o.put("body", new JSONObject(body));
            else
                o.put("error", error == null ? "failed" : error);
        } catch (Exception e) {
            try {
                o.put("ok", false).put("error", "invalid response");
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    private static JSONObject err(String message) {
        try {
            return new JSONObject().put("message", message);
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    private void askNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply();
            ui.post(() -> requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2));
        }
    }
}
