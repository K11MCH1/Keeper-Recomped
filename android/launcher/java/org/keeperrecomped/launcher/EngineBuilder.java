package org.keeperrecomped.launcher;

import android.app.ActivityManager;
import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Builds the KeeperFX engine on the phone from a KeeperFX source tree: applies the
 * Android seams (kit/seams.json, from tools/patch-keeperfx.py), generates ver_defs.h
 * and the window icon, compiles every unit in parallel and links libkeeperfx.so
 * against the app's libkrdeps.so, libSDL3.so and libc++_shared.so.
 * The recipe (flags, includes, link inputs) is kit/kit.json, taken from the PC build.
 */
final class EngineBuilder {
    interface Progress {
        void stage(String label, double fraction, String detail) throws Prepare.Cancelled;
        boolean cancelled();
    }

    /** This KeeperFX version no longer fits the Android seams; it needs an app update. */
    static final class Unbuildable extends IOException {
        Unbuildable(String message) { super(message); }
    }

    private final Context ctx;
    private final Toolchain tc;
    private final Progress progress;
    private final List<Process> processes = Collections.synchronizedList(new ArrayList<>());

    EngineBuilder(Context ctx, Toolchain tc, Progress progress) {
        this.ctx = ctx;
        this.tc = tc;
        this.progress = progress;
    }

    static File root(Context c) { return new File(c.getFilesDir(), "engine"); }

    /** Builds from {@code src} (a reduced KeeperFX checkout) and returns the installed library. */
    File build(File src, File work, String commit, String version, int buildNumber) throws Exception {
        progress.stage("Applying Android changes", 0, null);
        applySeams(src, version);
        File gen = new File(work, "gen");
        generate(src, gen, buildNumber, commit);
        List<File> units = units(src, gen);
        File objects = new File(work, "obj");
        Prepare.deleteTree(objects);
        if (!objects.mkdirs())
            throw new IOException("Cannot create " + objects);
        compile(units, src, gen, objects);

        progress.stage("Linking the engine", 0, null);
        File dir = new File(root(ctx), commit.substring(0, Math.min(12, commit.length())));
        dir.mkdirs();
        File partial = new File(dir, "libkeeperfx.so.partial");
        partial.delete();
        link(objects, partial);
        File lib = new File(dir, "libkeeperfx.so");
        if (!partial.renameTo(lib))
            throw new IOException("Cannot install the engine library");
        return lib;
    }

    // ---- source ----

    private void applySeams(File src, String version) throws IOException, JSONException {
        JSONObject doc = new JSONObject(Toolchain.read(new File(tc.kit, "seams.json")));
        File base = new File(src, doc.optString("base", "src"));
        JSONArray seams = doc.getJSONArray("seams");
        for (int i = 0; i < seams.length(); i++) {
            JSONObject seam = seams.getJSONObject(i);
            String name = seam.getString("file");
            File file = new File(base, name);
            if (!file.isFile())
                throw new Unbuildable("KeeperFX " + version + " has no " + name + ", which the Android engine changes."
                        + " This version needs an app update.");
            String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8).replace("\r\n", "\n");
            String old = seam.getString("old"), now = seam.getString("new");
            if (text.contains(now))
                continue;
            int first = text.indexOf(old);
            if (first < 0 || text.indexOf(old, first + 1) >= 0)
                throw new Unbuildable("KeeperFX " + version + " changed " + name + " where the Android engine hooks in."
                        + " This version needs an app update.");
            text = text.substring(0, first) + now + text.substring(first + old.length());
            Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void generate(File src, File gen, int buildNumber, String commit) throws IOException {
        Prepare.deleteTree(gen);
        gen.mkdirs();
        String mk = new String(Files.readAllBytes(new File(src, "version.mk").toPath()), StandardCharsets.UTF_8);
        java.util.Map<String, String> v = new java.util.HashMap<>();
        Matcher m = Pattern.compile("(?m)^(VER_MAJOR|VER_MINOR|VER_RELEASE)=(\\d+)").matcher(mk);
        while (m.find())
            v.put(m.group(1), m.group(2));
        if (v.size() != 3)
            throw new IOException("version.mk has no version numbers");
        v.put("BUILD_NUMBER", Integer.toString(buildNumber));
        v.put("PACKAGE_SUFFIX", "Android ARM64");
        v.put("GIT_REVISION", commit + "+android");
        v.put("VER_STRING", v.get("VER_MAJOR") + "." + v.get("VER_MINOR") + "." + v.get("VER_RELEASE") + "." + buildNumber + " Android ARM64");
        String template = new String(Files.readAllBytes(new File(src, "ver_defs.h.in").toPath()), StandardCharsets.UTF_8);
        StringBuffer out = new StringBuffer();
        Matcher t = Pattern.compile("\\$\\{(\\w+)\\}").matcher(template);
        while (t.find())
            t.appendReplacement(out, Matcher.quoteReplacement(v.getOrDefault(t.group(1), "")));
        t.appendTail(out);
        Files.write(new File(gen, "ver_defs.h").toPath(), out.toString().getBytes(StandardCharsets.UTF_8));
        byte[] icon = Files.readAllBytes(new File(src, "res/keeperfx_icon256-24bpp.png").toPath());
        StringBuilder c = new StringBuilder("const unsigned char kfx_window_icon_png[] = {");
        for (int i = 0; i < icon.length; i++)
            c.append(i == 0 ? "" : ",").append(String.format(Locale.ROOT, "0x%02x", icon[i] & 0xff));
        c.append("};\nconst unsigned int kfx_window_icon_png_size = sizeof(kfx_window_icon_png);\n");
        Files.write(new File(gen, "window_icon.c").toPath(), c.toString().getBytes(StandardCharsets.UTF_8));
    }

    private List<File> units(File src, File gen) throws JSONException {
        JSONObject sources = tc.recipe.getJSONObject("sources");
        List<Pattern> exclude = new ArrayList<>();
        JSONArray ex = sources.getJSONArray("exclude");
        for (int i = 0; i < ex.length(); i++)
            exclude.add(Pattern.compile(ex.getString(i)));
        List<File> units = new ArrayList<>();
        JSONArray roots = sources.getJSONArray("roots");
        for (int i = 0; i < roots.length(); i++)
            collect(new File(src, roots.getString(i)), src, exclude, units);
        Collections.sort(units);
        JSONArray extra = sources.getJSONArray("extra");
        for (int i = 0; i < extra.length(); i++)
            units.add(new File(src, extra.getString(i)));
        JSONArray nat = sources.getJSONArray("native");
        for (int i = 0; i < nat.length(); i++)
            units.add(new File(tc.kit, "native/" + nat.getString(i)));
        units.add(new File(gen, "window_icon.c"));
        return units;
    }

    private static void collect(File dir, File src, List<Pattern> exclude, List<File> out) {
        File[] list = dir.listFiles();
        if (list == null)
            return;
        for (File f : list) {
            if (f.isDirectory()) {
                collect(f, src, exclude, out);
                continue;
            }
            String n = f.getName();
            if (!n.endsWith(".c") && !n.endsWith(".cpp"))
                continue;
            String rel = "/" + src.toPath().relativize(f.toPath()).toString().replace(File.separatorChar, '/');
            boolean skip = false;
            for (Pattern p : exclude)
                skip |= p.matcher(rel).find();
            if (!skip)
                out.add(f);
        }
    }

    // ---- compile and link ----

    private List<String> common(File src, File gen) throws JSONException {
        List<String> args = new ArrayList<>(Arrays.asList("--target=" + tc.target, "--sysroot=" + tc.sysroot,
                "-resource-dir=" + tc.resource, "-fintegrated-cc1", "-O2", "-g0"));
        add(args, tc.recipe.getJSONArray("defines"), "", src, gen);
        add(args, tc.recipe.getJSONArray("includes"), "-I", src, gen);
        add(args, tc.recipe.getJSONArray("system_includes"), "-isystem", src, gen);
        args.add("-include");
        args.add(subst(tc.recipe.getString("force_include"), src, gen));
        return args;
    }

    private void add(List<String> args, JSONArray list, String prefix, File src, File gen) throws JSONException {
        for (int i = 0; i < list.length(); i++)
            args.add(prefix + subst(list.getString(i), src, gen));
    }

    private String subst(String s, File src, File gen) {
        return s.replace("@SRC@", src.getPath()).replace("@GEN@", gen.getPath()).replace("@KIT@", tc.kit.getPath());
    }

    private void compile(List<File> units, File src, File gen, File objects) throws Exception {
        List<String> common = common(src, gen);
        List<String> cFlags = strings(tc.recipe.getJSONArray("c")), cxxFlags = strings(tc.recipe.getJSONArray("cxx"));
        int jobs = jobs();
        AtomicInteger done = new AtomicInteger();
        int total = units.size();
        progress.stage("Compiling the engine", 0, "0 of " + total + " files, " + jobs + " at a time");
        ExecutorService pool = Executors.newFixedThreadPool(jobs);
        List<Future<?>> results = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            final File unit = units.get(i);
            final File object = new File(objects, String.format(Locale.ROOT, "%04d.o", i));
            results.add(pool.submit(() -> {
                boolean cxx = unit.getName().endsWith(".cpp");
                List<String> cmd = new ArrayList<>();
                cmd.add(tc.clang().getPath());
                if (cxx)
                    cmd.add("--driver-mode=g++");
                cmd.addAll(cxx ? cxxFlags : cFlags);
                cmd.add(cxx ? "-std=gnu++20" : "-std=gnu11");
                cmd.addAll(common);
                cmd.addAll(Arrays.asList("-c", unit.getPath(), "-o", object.getPath()));
                run(unit.getName(), cmd);
                int n = done.incrementAndGet();
                progress.stage("Compiling the engine", (double) n / total, n + " of " + total + " files");
                return null;
            }));
        }
        pool.shutdown();
        try {
            for (Future<?> f : results)
                f.get();
        } catch (ExecutionException e) {
            pool.shutdownNow();
            stop();
            Throwable cause = e.getCause();
            throw cause instanceof Exception ? (Exception) cause : new IOException(cause);
        }
    }

    private void link(File objects, File out) throws Exception {
        File api = tc.apiLibraries();
        List<String> cmd = new ArrayList<>(Arrays.asList(tc.lld().getPath(), "-flavor", "gnu", "--sysroot=" + tc.sysroot,
                "-shared", "-o", out.getPath(), "-soname", "libkeeperfx.so", "-z", "now", "-z", "relro", "-z", "noexecstack",
                "-z", "max-page-size=16384", "--hash-style=gnu", "--eh-frame-hdr", "--build-id=sha1", "--no-undefined",
                "--gc-sections", "--fix-cortex-a53-843419", new File(api, "crtbegin_so.o").getPath()));
        File[] objs = objects.listFiles((d, n) -> n.endsWith(".o"));
        if (objs == null || objs.length == 0)
            throw new IOException("Nothing was compiled");
        Arrays.sort(objs);
        for (File o : objs)
            cmd.add(o.getPath());
        JSONArray libs = tc.recipe.getJSONArray("link");
        for (int i = 0; i < libs.length(); i++)
            cmd.add(tc.library(libs.getString(i)).getPath());
        cmd.add("-L" + api);
        JSONArray sys = tc.recipe.getJSONArray("system_libs");
        for (int i = 0; i < sys.length(); i++)
            cmd.add("-l" + sys.getString(i));
        String b = tc.builtins.getPath(), u = tc.unwind.getPath();
        cmd.addAll(Arrays.asList(b, u, tc.atomic.getPath(), "-lc", b, u, "-ldl", new File(api, "crtend_so.o").getPath()));
        run("link", cmd);
    }

    /** One compiler per core, as far as free memory allows (about 450 MB each). */
    private int jobs() {
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        ctx.getSystemService(ActivityManager.class).getMemoryInfo(memory);
        long byMemory = memory.availMem / (450L << 20);
        return (int) Math.max(1, Math.min(Math.min(Runtime.getRuntime().availableProcessors(), 8), byMemory));
    }

    private void run(String step, List<String> cmd) throws Exception {
        if (progress.cancelled())
            throw new Prepare.Cancelled();
        File log = new File(ctx.getCacheDir(), "build-" + step.replaceAll("[^A-Za-z0-9._-]", "_") + ".log");
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log);
        pb.environment().put("LD_LIBRARY_PATH", tc.compilerLibraryPath());
        pb.environment().put("TMPDIR", ctx.getCacheDir().getPath());
        Process p = pb.start();
        processes.add(p);
        try {
            int code = p.waitFor();
            if (progress.cancelled())
                throw new Prepare.Cancelled();
            if (code != 0) {
                String tail = tail(log, 14);
                AppLog.w("engine build step " + step + " failed (" + code + "):\n" + tail);
                throw new IOException("Building the engine failed at " + step + ":\n" + tail);
            }
        } finally {
            processes.remove(p);
        }
        log.delete();
    }

    void stop() {
        synchronized (processes) {
            for (Process p : processes)
                p.destroy();
        }
    }

    private static List<String> strings(JSONArray a) throws JSONException {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < a.length(); i++)
            out.add(a.getString(i));
        return out;
    }

    static String tail(File file, int lines) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long from = Math.max(0, f.length() - 4096);
            f.seek(from);
            byte[] b = new byte[(int) (f.length() - from)];
            f.readFully(b);
            String[] all = new String(b, StandardCharsets.UTF_8).split("\n");
            return String.join("\n", Arrays.copyOfRange(all, Math.max(0, all.length - lines), all.length));
        } catch (IOException e) {
            return "";
        }
    }
}
