package org.keeperrecomped.launcher;

import android.app.ActivityManager;
import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The Prepare &amp; Build pipeline and the other long tasks (updates, workshop
 * installs). Runs on PrepareService's worker thread and reports progress as JSON.
 *
 * Prepare: import the picked folder → KeeperFX stable "complete" release (unless a
 * KeeperFX folder was imported) → the 20 original DK files KeeperFX needs → alpha
 * patch (Alpha channel) → keeperfx.cfg → engine build hook (original recompilation).
 * Windows binaries in KeeperFX archives are never extracted: the Android engine is
 * the APK's native library.
 */
final class Prepare {
    static final String TAG = "KeeperLauncher";

    interface Listener {
        void update(JSONObject progress);
    }

    static final class Cancelled extends IOException {
        Cancelled() { super("Cancelled"); }
    }

    // The original files KeeperFX itself does not ship (from the KeeperFX launcher's dkfiles.cpp).
    static final String[] DK_DATA = {"bluepal.dat", "bluepall.dat", "dogpal.pal", "hitpall.dat", "lightng.pal",
            "redpal.col", "redpall.dat", "slab0-0.dat", "slab0-1.dat", "vampal.pal", "whitepal.col"};
    static final String[] DK_SOUND = {"atmos1.sbk", "atmos2.sbk", "bullfrog.sbk"};
    static final String[] DK_MUSIC = {"keeper02.ogg", "keeper03.ogg", "keeper04.ogg", "keeper05.ogg", "keeper06.ogg", "keeper07.ogg"};

    static final String BUILD_HOOK = "org.keeperrecomped.game.EngineBuild";

    private final Context ctx;
    private final Settings settings;
    private final KfxApi api;
    private final Listener listener;
    private volatile boolean cancelled;
    private String task = "prepare";
    private int base, span; // current step's slice of the overall bar
    private String label = "";

    Prepare(Context context, Listener listener) {
        this.ctx = context.getApplicationContext();
        this.settings = new Settings(ctx);
        this.api = new KfxApi(ctx);
        this.listener = listener;
    }

    void cancel() { cancelled = true; }

    boolean isCancelled() { return cancelled; }

    private void check() throws Cancelled {
        if (cancelled)
            throw new Cancelled();
    }

    private void step(int from, int to, String text) throws Cancelled {
        check();
        base = from;
        span = to - from;
        label = text;
        emit(0, null);
        AppLog.i("step " + text);
    }

    private void emit(double fraction, String detail) {
        try {
            JSONObject p = new JSONObject();
            p.put("task", task).put("running", true).put("label", label)
                    .put("percent", Math.min(100, base + (int) Math.round(span * Math.max(0, Math.min(1, fraction)))));
            if (detail != null)
                p.put("detail", detail);
            listener.update(p);
        } catch (JSONException ignored) {
        }
    }

    static String mb(long bytes) {
        return String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0);
    }

    // ---- Prepare & Build ----

    void prepare(Uri tree) throws Exception {
        task = "prepare";
        JSONObject state = State.load(ctx);
        File dk = Paths.dk(ctx), kfx = Paths.kfx(ctx);
        boolean kfxFromFolder = state.optBoolean("kfx_from_folder");

        if (tree != null) {
            step(0, 25, "Reading the game folder");
            Importer importer = new Importer(ctx.getContentResolver(), tree);
            Importer.Found found = importer.find();
            emit(0, "Found " + (found.keeperfx ? "a KeeperFX installation" : "Dungeon Keeper") + " in " + found.path
                    + " (" + found.files + " files, " + mb(found.bytes) + ")");
            step(0, 25, found.keeperfx ? "Importing KeeperFX" : "Importing Dungeon Keeper");
            File dest = found.keeperfx ? kfx : dk;
            importer.copy(found, dest, (done, total, file) -> {
                emit(total > 0 ? (double) done / total : 0, mb(done) + " / " + mb(total) + "  " + file);
                return !cancelled;
            });
            kfxFromFolder = found.keeperfx;
            state = State.load(ctx);
            state.put("source_path", found.path).put("source_uri", tree.toString()).put("source_files", found.files)
                    .put("kfx_from_folder", kfxFromFolder).put("imported_at", now());
            if (found.keeperfx) {
                state.put("kfx_version", "folder");
                // KeeperFX folders hold the DK files it needs; keep a lowercase copy for the original profile.
                copyDkSubset(kfx, dk);
            } else {
                state.put("dk_has_exe", found.hasExe);
            }
            State.save(ctx, state);
        } else if (!new File(dk, "data/bluepal.dat").isFile() && !new File(kfx, "data/bluepal.dat").isFile()) {
            throw new IOException("Choose your Dungeon Keeper folder first.");
        }

        if (!kfxFromFolder)
            installStable(25, 72);

        step(72, 74, "Adding the original game files");
        copyDkSubset(dk, kfx);

        if ("ALPHA".equals(settings.get("release_channel"))) {
            applyAlpha(74, 90);
        }

        step(90, 92, "Writing settings");
        fromTemplates(kfx);
        ConfigWriter.write(kfx, settings);

        runBuildHook(92, 100);

        state = State.load(ctx);
        state.put("prepared", true).put("prepared_at", now());
        State.save(ctx, state);
        base = 100; span = 0; label = "Ready";
        emit(1, null);
    }

    /**
     * The stable "complete" release, when it is newer than the installed one, or
     * when the Stable channel must undo a previously applied alpha patch.
     */
    void installStable(int from, int to) throws Exception {
        File kfx = Paths.kfx(ctx);
        JSONObject state = State.load(ctx);
        JSONObject stable;
        try {
            stable = api.stable();
        } catch (IOException e) {
            if (new File(kfx, "fxdata").isDirectory()) {
                AppLog.w("offline, keeping installed KeeperFX: " + e);
                return;
            }
            throw new IOException("Cannot reach keeperfx.net to download KeeperFX: " + e.getMessage());
        }
        boolean current = stable.optString("version").equals(state.optString("kfx_version")) && new File(kfx, "fxdata").isDirectory();
        boolean undoAlpha = "STABLE".equals(settings.get("release_channel")) && !state.optString("alpha_version").isEmpty();
        if (current && !undoAlpha)
            return;
        if (settings.bool("backup_saves"))
            backupSaves(kfx);
        String url = stable.getString("download_url");
        File archive = new File(Paths.downloads(ctx), fileName(url));
        int mid = from + (to - from) * 7 / 10;
        step(from, mid, "Downloading " + stable.optString("name", "KeeperFX"));
        Net.download(url, archive, stable.optLong("size_in_bytes", -1), (done, total) -> {
            emit(total > 0 ? (double) done / total : 0, mb(done) + " / " + (total > 0 ? mb(total) : "?"));
            return !cancelled;
        });
        step(mid, to, "Installing " + stable.optString("name", "KeeperFX"));
        extract7z(archive, kfx, null);
        state = State.load(ctx);
        state.put("kfx_version", stable.optString("version")).put("alpha_version", "").put("alpha_title", "");
        State.save(ctx, state);
        discard(archive);
    }

    /** The alpha patch on top of the installed stable release, when it is newer. */
    void applyAlpha(int from, int to) throws Exception {
        JSONObject state = State.load(ctx);
        JSONObject alpha = api.alpha();
        String version = alpha.optString("version");
        if (version.equals(state.optString("alpha_version")))
            return;
        String kfxVersion = state.optString("kfx_version");
        if (!kfxVersion.isEmpty() && !"folder".equals(kfxVersion) && !version.startsWith(majorMinor(kfxVersion)))
            AppLog.w("alpha " + version + " over stable " + kfxVersion);
        File kfx = Paths.kfx(ctx);
        if (settings.bool("backup_saves"))
            backupSaves(kfx);
        String url = alpha.getString("download_url");
        File archive = new File(Paths.downloads(ctx), alpha.optString("filename", fileName(url)));
        int mid = from + (to - from) * 2 / 3;
        step(from, mid, "Downloading alpha patch " + version);
        Net.download(url, archive, alpha.optLong("size_in_bytes", -1), (done, total) -> {
            emit(total > 0 ? (double) done / total : 0, mb(done) + " / " + (total > 0 ? mb(total) : "?"));
            return !cancelled;
        });
        step(mid, to, "Applying alpha patch " + version);
        extract7z(archive, kfx, null);
        state = State.load(ctx);
        state.put("alpha_version", version).put("alpha_title", alpha.optString("workflow_title"));
        State.save(ctx, state);
        discard(archive);
    }

    /** Update task: newest alpha (or stable) without re-importing. */
    void update() throws Exception {
        task = "update";
        if (!new File(Paths.kfx(ctx), "fxdata").isDirectory())
            throw new IOException("Prepare the game first.");
        if (!State.load(ctx).optBoolean("kfx_from_folder"))
            installStable(0, 60);
        copyDkSubset(Paths.dk(ctx), Paths.kfx(ctx));
        if ("ALPHA".equals(settings.get("release_channel")))
            applyAlpha(60, 95);
        step(95, 100, "Writing settings");
        fromTemplates(Paths.kfx(ctx));
        ConfigWriter.write(Paths.kfx(ctx), settings);
        base = 100; span = 0; label = "Up to date";
        emit(1, null);
    }

    private void runBuildHook(int from, int to) throws Exception {
        Class<?> hook;
        try {
            hook = Class.forName(BUILD_HOOK);
        } catch (ClassNotFoundException e) {
            AppLog.i("no engine build hook in this APK");
            State.put(ctx, "build_hook", false);
            return;
        }
        step(from, to, "Building");
        Method build = hook.getMethod("build", Context.class, File.class, File.class, BuildProgress.class);
        File out = Paths.build(ctx);
        out.mkdirs();
        build.invoke(null, ctx, Paths.dk(ctx), out, new BuildProgress() {
            @Override
            public void progress(int percent, String message) {
                emit(percent / 100.0, message);
            }

            @Override
            public boolean cancelled() {
                return cancelled;
            }
        });
        State.put(ctx, "build_hook", true);
        State.put(ctx, "built_at", now());
    }

    // ---- Workshop ----

    void installWorkshop(String id, String name, String url, String filename, long size) throws Exception {
        task = "workshop";
        File kfx = Paths.kfx(ctx);
        if (!new File(kfx, "fxdata").isDirectory())
            throw new IOException("Prepare KeeperFX before installing workshop items.");
        File archive = new File(Paths.downloads(ctx), "workshop-" + id + "-" + safeName(filename));
        step(0, 70, "Downloading " + name);
        Net.download(url, archive, size, (done, total) -> {
            emit(total > 0 ? (double) done / total : 0, mb(done) + " / " + (total > 0 ? mb(total) : "?"));
            return !cancelled;
        });
        step(70, 100, "Installing " + name);
        List<String> names = new ArrayList<>();
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip")) {
            try (ZipFile zip = new ZipFile(archive)) {
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry z = e.nextElement();
                    if (!z.isDirectory())
                        names.add(z.getName().replace('\\', '/'));
                }
                String strip = commonPrefix(names);
                int i = 0;
                for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                    ZipEntry z = e.nextElement();
                    check();
                    if (z.isDirectory())
                        continue;
                    String rel = z.getName().replace('\\', '/').substring(strip.length());
                    if (!safeRelative(rel) || skipBinary(rel))
                        continue;
                    File out = new File(kfx, rel);
                    out.getParentFile().mkdirs();
                    try (InputStream in = zip.getInputStream(z)) {
                        Files.copy(in, out.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    emit((double) ++i / names.size(), rel);
                }
            }
        } else if (lower.endsWith(".7z")) {
            String err = SevenZip.extract(archive.getPath(), kfx.getPath(), new SevenZip.Callback() {
                @Override
                public String map(String path, long s) {
                    names.add(path);
                    return null; // listing pass
                }

                @Override
                public boolean progress(long done, long total) {
                    return !cancelled;
                }
            });
            if (err != null)
                throw new IOException(err);
            String strip = commonPrefix(names);
            extract7z(archive, kfx, strip);
        } else {
            throw new IOException("Unsupported workshop file " + filename + "; open it on keeperfx.net instead.");
        }
        JSONObject state = State.load(ctx);
        JSONObject ws = state.optJSONObject("workshop");
        if (ws == null)
            ws = new JSONObject();
        ws.put(id, new JSONObject().put("name", name).put("file", filename).put("installed_at", now()));
        state.put("workshop", ws);
        State.save(ctx, state);
        discard(archive);
        base = 100; span = 0; label = "Installed " + name;
        emit(1, null);
    }

    /**
     * Workshop archives often wrap everything in one folder named after the map;
     * strip it unless it is already a KeeperFX folder (levels, campgns, ...).
     */
    static String commonPrefix(List<String> names) {
        if (names.isEmpty())
            return "";
        String first = names.get(0);
        int slash = first.indexOf('/');
        if (slash <= 0)
            return "";
        String prefix = first.substring(0, slash + 1);
        for (String n : names)
            if (!n.startsWith(prefix))
                return "";
        String top = prefix.substring(0, prefix.length() - 1).toLowerCase(Locale.ROOT);
        for (String known : new String[]{"levels", "campgns", "creatrs", "fxdata", "data", "ldata", "sound", "music", "mods", "save"})
            if (top.equals(known))
                return "";
        return prefix;
    }

    // ---- helpers ----

    private void extract7z(File archive, File dest, String stripPrefix) throws IOException {
        long block = SevenZip.largestBlock(archive.getPath());
        ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        AppLog.i(archive.getName() + ": largest block " + mb(block) + ", available " + mb(mi.availMem));
        if (block < 0)
            throw new IOException(archive.getName() + " is not a readable 7z archive; it will be downloaded again.");
        if (block > mi.availMem * 0.7)
            throw new IOException(archive.getName() + " needs " + mb(block) + " of memory to unpack, more than is free ("
                    + mb(mi.availMem) + "). Close other apps and retry, or import a KeeperFX folder copied from a PC.");
        dest.mkdirs();
        String err = SevenZip.extract(archive.getPath(), dest.getPath(), new SevenZip.Callback() {
            @Override
            public String map(String path, long size) {
                String rel = path;
                if (stripPrefix != null && !stripPrefix.isEmpty()) {
                    if (!rel.startsWith(stripPrefix))
                        return null;
                    rel = rel.substring(stripPrefix.length());
                }
                if (!safeRelative(rel) || skipBinary(rel) || rel.equalsIgnoreCase("keeperfx.cfg"))
                    return null;
                return rel;
            }

            @Override
            public boolean progress(long done, long total) {
                emit(total > 0 ? (double) done / total : 0, mb(done) + " / " + mb(total));
                return !cancelled;
            }
        });
        if (err != null) {
            if ("cancelled".equals(err))
                throw new Cancelled();
            if (err.contains("corrupt") || err.contains("truncated") || err.contains("not a 7z"))
                archive.delete();
            throw new IOException(archive.getName() + ": " + err);
        }
    }

    static boolean skipBinary(String rel) {
        String n = rel.toLowerCase(Locale.ROOT);
        String base = n.substring(n.lastIndexOf('/') + 1);
        return n.endsWith(".exe") || n.endsWith(".dll") || n.endsWith(".map") || n.endsWith(".pdb")
                || base.startsWith("keeperfx-launcher") || base.startsWith("unins") || base.equals("launch.sh");
    }

    static boolean safeRelative(String rel) {
        if (rel.isEmpty() || rel.startsWith("/") || rel.contains("\\") || rel.contains(":"))
            return false;
        for (String part : rel.split("/"))
            if (part.equals(".."))
                return false;
        return true;
    }

    /** The 20 DK files KeeperFX needs, from a DK copy into a game root (both lowercase). */
    private void copyDkSubset(File from, File to) throws IOException {
        int missing = 0;
        for (String f : DK_DATA)
            missing += copyIfPresent(new File(from, "data/" + f), new File(to, "data/" + f)) ? 0 : 1;
        for (String f : DK_SOUND)
            missing += copyIfPresent(new File(from, "sound/" + f), new File(to, "sound/" + f)) ? 0 : 1;
        for (String f : DK_MUSIC) {
            File src = new File(from, "music/" + f);
            if (!src.isFile())
                src = new File(from, f);
            copyIfPresent(src, new File(to, "music/" + f)); // music is optional
        }
        if (missing > 0 && !new File(to, "data/bluepal.dat").isFile())
            throw new IOException(missing + " required Dungeon Keeper files are missing (data/*.dat, sound/*.sbk).");
    }

    private static boolean copyIfPresent(File src, File dst) throws IOException {
        if (dst.isFile() && src.isFile() && dst.length() == src.length())
            return true;
        if (!src.isFile())
            return dst.isFile();
        dst.getParentFile().mkdirs();
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        return true;
    }

    /**
     * KeeperFX ships editable files as underscore templates (_keeperfx.cfg,
     * mods/_load_order.cfg); like its launcher, create the real file once.
     * keeperfx.cfg itself is ConfigWriter's job.
     */
    static void fromTemplates(File kfx) throws IOException {
        copyIfPresent(new File(kfx, "mods/_load_order.cfg"), new File(kfx, "mods/load_order.cfg"), false);
    }

    private static void copyIfPresent(File src, File dst, boolean replace) throws IOException {
        if (!src.isFile() || (dst.isFile() && !replace))
            return;
        Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private void backupSaves(File kfx) throws IOException {
        File save = new File(kfx, "save");
        String[] list = save.list();
        if (list == null || list.length == 0)
            return;
        File backup = new File(ctx.getFilesDir(), "save-backup/" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()));
        backup.mkdirs();
        for (String name : list) {
            File f = new File(save, name);
            if (f.isFile())
                try (InputStream in = new FileInputStream(f); OutputStream out = new FileOutputStream(new File(backup, name))) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0)
                        out.write(buf, 0, n);
                }
        }
    }

    private void discard(File archive) {
        if (!settings.bool("keep_downloads"))
            archive.delete();
    }

    private static String majorMinor(String version) {
        String[] p = version.split("\\.");
        return p.length >= 2 ? p[0] + "." + p[1] : version;
    }

    static String fileName(String url) {
        String s = url.substring(url.lastIndexOf('/') + 1);
        int q = s.indexOf('?');
        return safeName(q >= 0 ? s.substring(0, q) : s);
    }

    static String safeName(String s) {
        String n = s.replaceAll("[^A-Za-z0-9._-]", "_");
        return n.isEmpty() ? "download" : n;
    }

    static String now() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.ROOT).format(new Date());
    }
}
