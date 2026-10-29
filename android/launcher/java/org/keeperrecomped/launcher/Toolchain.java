package org.keeperrecomped.launcher;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.system.ErrnoException;
import android.system.Os;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * The phone-side compiler (pattern from the Badman launcher): Termux's Clang and LLD
 * run from the APK's read-only library folder, because Android refuses to run
 * programs from writable storage. Headers, link stubs and the engine kit are unpacked
 * once per app version into files/toolchain.
 */
final class Toolchain {
    final File root, sysroot, resource, builtins, unwind, atomic, kit, aliases;
    final String target, triple, libraries;
    final int api;
    final JSONObject recipe;

    private Toolchain(Context c, File root) throws IOException, JSONException {
        this.root = root;
        JSONObject tc = new JSONObject(read(new File(root, "tc/toolchain.json")));
        sysroot = new File(root, "tc/sysroot");
        resource = new File(root, "tc/resource");
        builtins = new File(root, "tc/" + tc.getString("builtins"));
        unwind = new File(root, "tc/builtins/libunwind.a");
        atomic = new File(root, "tc/builtins/libatomic.a");
        kit = new File(root, "kit");
        aliases = new File(root, "aliases");
        target = tc.getString("target");
        triple = tc.getString("triple");
        api = tc.getInt("api");
        libraries = c.getApplicationInfo().nativeLibraryDir;
        recipe = new JSONObject(read(new File(kit, "kit.json")));
    }

    /** True when this APK carries the compiler (engineering builds may leave it out). */
    static boolean available(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libkr_clang_tool.so").isFile();
    }

    private static String kitId;

    /** Identifies this app's engine kit (Android code and recipe); a phone-built engine from another kit needs a rebuild. */
    static synchronized String kitId(Context c) {
        if (kitId == null) {
            try {
                kitId = new JSONObject(readAsset(c, "compiler.json")).optString("kit_id");
            } catch (IOException | JSONException e) {
                kitId = "";
            }
        }
        return kitId;
    }

    File library(String name) { return new File(libraries, name); }
    File clang() { return library("libkr_clang_tool.so"); }
    File lld() { return library("libkr_lld_tool.so"); }
    File apiLibraries() { return new File(sysroot, "usr/lib/" + triple + "/" + api); }
    /** The compiler's own libraries first (Termux's libc++ under its soname), then the app's. */
    String compilerLibraryPath() { return aliases.getPath() + ":" + libraries; }

    static synchronized Toolchain prepare(Context c) throws IOException {
        if (!available(c))
            throw new IOException("This build of the app has no engine compiler.");
        File root = new File(c.getFilesDir(), "toolchain");
        File stamp = new File(root, ".unpacked");
        String version;
        try {
            PackageInfo info = c.getPackageManager().getPackageInfo(c.getPackageName(), 0);
            version = info.lastUpdateTime + "/" + info.getLongVersionCode();
        } catch (Exception e) {
            throw new IOException("Cannot read the app version", e);
        }
        if (!version.equals(read(stamp))) {
            Prepare.deleteTree(root);
            unpack(c, "toolchain.zip", new File(root, "tc"));
            unpack(c, "engine-kit.zip", new File(root, "kit"));
            write(stamp, version);
        }
        try {
            Toolchain t = new Toolchain(c, root);
            t.link(new JSONObject(readAsset(c, "compiler.json")).getJSONObject("aliases"));
            return t;
        } catch (JSONException e) {
            throw new IOException("The packaged compiler description is damaged", e);
        }
    }

    /** Library names the compiler asks for, pointing at the files the APK holds them under. */
    private void link(JSONObject names) throws IOException, JSONException {
        Prepare.deleteTree(aliases);
        if (!aliases.mkdirs())
            throw new IOException("Cannot create the compiler library folder");
        for (Iterator<String> it = names.keys(); it.hasNext(); ) {
            String soname = it.next();
            if (soname.contains("/") || soname.startsWith("."))
                throw new IOException("Invalid library name " + soname);
            try {
                Os.symlink(library(names.getString(soname)).getPath(), new File(aliases, soname).getPath());
            } catch (ErrnoException e) {
                throw new IOException("Cannot link " + soname, e);
            }
        }
    }

    private static void unpack(Context c, String asset, File dest) throws IOException {
        if (!dest.mkdirs())
            throw new IOException("Cannot create " + dest);
        String base = dest.getCanonicalPath() + File.separator;
        try (ZipInputStream zip = new ZipInputStream(c.getAssets().open(asset))) {
            byte[] buffer = new byte[1 << 16];
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                File file = new File(dest, e.getName());
                if (!file.getCanonicalPath().startsWith(base))
                    throw new IOException("Invalid compiler file " + e.getName());
                if (e.isDirectory()) {
                    file.mkdirs();
                    continue;
                }
                file.getParentFile().mkdirs();
                try (OutputStream out = new FileOutputStream(file)) {
                    for (int n; (n = zip.read(buffer)) > 0; )
                        out.write(buffer, 0, n);
                }
            }
        }
    }

    static String readAsset(Context c, String name) throws IOException {
        try (InputStream in = c.getAssets().open(name)) {
            return KfxSource.read(in);
        }
    }

    static String read(File file) {
        try {
            return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : "";
        } catch (IOException e) {
            return "";
        }
    }

    static void write(File file, String text) throws IOException {
        File partial = new File(file.getPath() + ".partial");
        Files.write(partial.toPath(), text.getBytes(StandardCharsets.UTF_8));
        if (!partial.renameTo(file))
            throw new IOException("Cannot write " + file);
    }
}
