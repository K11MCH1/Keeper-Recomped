package org.keeperrecomped.launcher;

import android.system.ErrnoException;
import android.system.Os;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Builds the Play Original game root from the KeeperFX root. KeeperFX lists every
 * campgns/*.cfg and only honours -campaign together with -level, so the classic
 * profile gets its own tree: symbolic links to the KeeperFX folders and files,
 * only the original campaign (keeporig*), no mods, and separate saves/replays.
 * Android forbids hard links for apps (API 29+), so links are symbolic; the
 * engine's fopen/opendir/stat follow them. Copies are the fallback.
 */
final class ClassicRoot {
    private static final String MARK = ".classic-source";

    private ClassicRoot() {}

    /** Identity of the KeeperFX tree it was linked from; a change means relink. */
    static String signature(File kfx, String state) {
        return state + "|" + new File(kfx, "fxdata").lastModified() + "|" + new File(kfx, "campgns").lastModified();
    }

    static boolean current(File original, String signature) {
        try {
            File mark = new File(original, MARK);
            return mark.isFile() && new String(Files.readAllBytes(mark.toPath()), StandardCharsets.UTF_8).equals(signature);
        } catch (IOException e) {
            return false;
        }
    }

    static void sync(File kfx, File original, String signature) throws IOException {
        if (!original.isDirectory() && !original.mkdirs())
            throw new IOException("Cannot create " + original);
        // Keep the classic profile's own saves, replays, screenshots and settings.
        File[] old = original.listFiles();
        if (old != null)
            for (File f : old)
                if (!keep(f.getName()))
                    delete(f);
        File[] top = kfx.listFiles();
        if (top == null)
            throw new IOException("KeeperFX is not prepared");
        for (File f : top) {
            String n = f.getName().toLowerCase(Locale.ROOT);
            if (keep(n) || n.equals("mods") || n.endsWith(".log") || n.equals(MARK) || n.endsWith(".part"))
                continue;
            if (n.equals("campgns")) {
                File dst = new File(original, f.getName());
                dst.mkdirs();
                File[] list = f.listFiles();
                if (list != null)
                    for (File c : list) {
                        String cn = c.getName().toLowerCase(Locale.ROOT);
                        if (cn.equals("keeporig.cfg") || cn.equals("keeporig") || cn.startsWith("keeporig_") || cn.equals("campgn_order.txt"))
                            link(c, new File(dst, c.getName()));
                    }
            } else {
                link(f, new File(original, f.getName()));
            }
        }
        new File(original, "save").mkdirs();
        new File(original, "mods").mkdirs();
        Prepare.fromTemplates(original); // empty load order: no mods in the classic profile
        Files.write(new File(original, MARK).toPath(), signature.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean keep(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.equals("save") || n.equals("replays") || n.equals("scrshots") || n.equals("keeperfx.cfg");
    }

    private static void link(File src, File dst) throws IOException {
        try {
            Os.symlink(src.getAbsolutePath(), dst.getPath());
            return;
        } catch (ErrnoException e) {
            AppLog.w("symlink " + dst + ": " + e.getMessage() + "; copying");
        }
        copy(src, dst);
    }

    private static void copy(File src, File dst) throws IOException {
        if (src.isDirectory()) {
            if (!dst.isDirectory() && !dst.mkdirs())
                throw new IOException("Cannot create " + dst);
            File[] list = src.listFiles();
            if (list != null)
                for (File f : list)
                    copy(f, new File(dst, f.getName()));
        } else {
            Files.copy(src.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Removes a file, a directory tree, or a link (never following it). */
    private static void delete(File f) throws IOException {
        if (f.isDirectory() && !java.nio.file.Files.isSymbolicLink(f.toPath())) {
            File[] list = f.listFiles();
            if (list != null)
                for (File c : list)
                    delete(c);
        }
        if (!f.delete() && (f.exists() || java.nio.file.Files.isSymbolicLink(f.toPath())))
            throw new IOException("Cannot remove " + f);
    }
}
