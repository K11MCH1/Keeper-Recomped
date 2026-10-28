package org.keeperrecomped.launcher;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Plain HTTPS GETs and resumable downloads. */
final class Net {
    private Net() {}

    static final String USER_AGENT = "KeeperRecomped/0.1 (Android launcher)";

    /**
     * Engineering builds only: fetch through scripts/android/dev_relay.py over
     * `adb reverse` when the test phone has no internet of its own.
     */
    static volatile String relay;

    static void configure(android.content.Context c) {
        boolean debuggable = (c.getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        relay = debuggable ? c.getSharedPreferences("launcher", android.content.Context.MODE_PRIVATE).getString("debug_relay", null) : null;
    }

    private static String route(String url) {
        String r = relay;
        if (r == null || r.isEmpty())
            return url;
        try {
            return r + "/fetch?u=" + java.net.URLEncoder.encode(url, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            return url;
        }
    }

    interface Progress {
        /** False cancels. total is -1 when unknown. */
        boolean update(long done, long total);
    }

    static String getString(String url) throws IOException {
        HttpURLConnection c = open(url, -1);
        try {
            int code = c.getResponseCode();
            if (code != 200)
                throw new IOException("HTTP " + code + " for " + url);
            try (InputStream in = c.getInputStream()) {
                return new String(readAll(in), StandardCharsets.UTF_8);
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] getBytes(String url, int limit) throws IOException {
        HttpURLConnection c = open(url, -1);
        try {
            if (c.getResponseCode() != 200)
                throw new IOException("HTTP " + c.getResponseCode() + " for " + url);
            try (InputStream in = c.getInputStream()) {
                byte[] data = readAll(in);
                if (data.length > limit)
                    throw new IOException("Response too large");
                return data;
            }
        } finally {
            c.disconnect();
        }
    }

    /**
     * Downloads to {@code target}, resuming a previous {@code target.part} when the
     * server supports ranges. Returns once {@code target} is complete.
     */
    static void download(String url, File target, long expectedSize, Progress progress) throws IOException {
        if (target.isFile() && (expectedSize <= 0 || target.length() == expectedSize))
            return;
        File part = new File(target.getPath() + ".part");
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs())
            throw new IOException("Cannot create " + parent);
        long have = part.isFile() ? part.length() : 0;
        if (expectedSize > 0 && have > expectedSize) {
            part.delete();
            have = 0;
        }
        HttpURLConnection c = open(url, have);
        try {
            int code = c.getResponseCode();
            boolean append = code == 206;
            if (code == 416 && expectedSize > 0 && have == expectedSize) {
                append = true; // already complete
            } else if (code != 200 && code != 206) {
                throw new IOException("HTTP " + code + " downloading " + url);
            }
            if (!append)
                have = 0;
            long length = c.getContentLengthLong();
            long total = length >= 0 ? have + length : expectedSize;
            if (code != 416) {
                try (InputStream in = c.getInputStream(); OutputStream out = new FileOutputStream(part, append)) {
                    byte[] buf = new byte[1 << 16];
                    long done = have, lastReport = 0;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        if (done - lastReport >= (1 << 18) || done == total) {
                            lastReport = done;
                            if (progress != null && !progress.update(done, total))
                                throw new IOException("cancelled");
                        }
                    }
                }
            }
            if (expectedSize > 0 && part.length() != expectedSize)
                throw new IOException("Download incomplete: " + part.length() + " of " + expectedSize + " bytes");
            if (target.exists())
                target.delete();
            if (!part.renameTo(target))
                throw new IOException("Cannot finish " + target);
        } finally {
            c.disconnect();
        }
    }

    private static HttpURLConnection open(String url, long from) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(route(url)).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", USER_AGENT);
        if (from > 0)
            c.setRequestProperty("Range", "bytes=" + from + "-");
        return c;
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0)
            out.write(buf, 0, n);
        return out.toByteArray();
    }
}
