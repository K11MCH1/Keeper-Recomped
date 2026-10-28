package org.keeperrecomped.launcher;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Launcher log in files/logs/launcher.log as well as logcat: some retail ROMs
 * (the NX809J among them) set log.tag=S and drop every app log line.
 */
final class AppLog {
    private static final long MAX = 512 * 1024;
    private static volatile File file;

    private AppLog() {}

    static void init(Context c) {
        if (file == null) {
            File dir = new File(c.getFilesDir(), "logs");
            dir.mkdirs();
            file = new File(dir, "launcher.log");
        }
    }

    static void i(String msg) { Log.i(Prepare.TAG, msg); write("I", msg, null); }

    static void w(String msg) { Log.w(Prepare.TAG, msg); write("W", msg, null); }

    static void e(String msg, Throwable t) { Log.e(Prepare.TAG, msg, t); write("E", msg, t); }

    private static synchronized void write(String level, String msg, Throwable t) {
        File f = file;
        if (f == null)
            return;
        try {
            if (f.length() > MAX) {
                File old = new File(f.getPath() + ".1");
                old.delete();
                f.renameTo(old);
            }
            String line = new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + level + " " + msg
                    + (t != null ? " :: " + Log.getStackTraceString(t) : "") + "\n";
            try (FileOutputStream out = new FileOutputStream(f, true)) {
                out.write(line.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }
}
