package org.keeperrecomped.launcher;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * KeeperFX versions and their source code, for building the engine on the phone.
 *
 * keeperfx.net lists every alpha patch (downloads page) and the latest alpha's build
 * run; KeeperFX's public GitHub build runs name the commit each alpha was built from.
 * Stable releases are tags. Source comes from GitHub's ordinary source download.
 */
final class KfxSource {
    private KfxSource() {}

    static final String ALPHA_PAGE = "https://keeperfx.net/downloads/alpha";
    static final String RUNS = "https://api.github.com/repos/dkfans/keeperfx/actions/workflows/build-alpha-patch-signed.yml/runs";
    static final String RUN = "https://api.github.com/repos/dkfans/keeperfx/actions/runs/";
    static final String SOURCE_ZIP = "https://codeload.github.com/dkfans/keeperfx/zip/";

    private static final Pattern ALPHA_ROW = Pattern.compile(
            "keeperfx-(\\d+)_(\\d+)_(\\d+)_(\\d+)-Alpha-patch\\s*</a>\\s*</td>\\s*<td>([^<]*)</td>.*?<td>\\s*(\\d{4}-\\d\\d-\\d\\d), (\\d\\d:\\d\\d)\\s*</td>\\s*<td>(.*?)</td>",
            Pattern.DOTALL);

    /** Every alpha patch on keeperfx.net, newest first: {version, build, size, date (ISO, UTC), title, download_url}. */
    static JSONArray alphas(Context c) throws IOException {
        File cache = new File(Paths.apiCache(c), "alphas.json");
        try {
            String page = new String(Net.getBytes(ALPHA_PAGE, 16 << 20), StandardCharsets.UTF_8);
            JSONArray list = new JSONArray();
            Matcher m = ALPHA_ROW.matcher(page);
            while (m.find()) {
                String version = m.group(1) + "." + m.group(2) + "." + m.group(3) + "." + m.group(4);
                String file = "keeperfx-" + m.group(1) + "_" + m.group(2) + "_" + m.group(3) + "_" + m.group(4) + "-Alpha-patch.7z";
                list.put(new JSONObject().put("version", version).put("build", Integer.parseInt(m.group(4)))
                        .put("size", m.group(5).trim()).put("date", m.group(6) + "T" + m.group(7) + ":00Z")
                        .put("title", html(m.group(8))).put("filename", file)
                        .put("download_url", "https://keeperfx.net/download/alpha/" + file));
            }
            if (list.length() == 0)
                throw new IOException("keeperfx.net listed no alpha patches");
            cache.getParentFile().mkdirs();
            Files.write(cache.toPath(), list.toString().getBytes(StandardCharsets.UTF_8));
            return list;
        } catch (IOException | JSONException e) {
            if (cache.isFile()) {
                try {
                    return new JSONArray(new String(Files.readAllBytes(cache.toPath()), StandardCharsets.UTF_8));
                } catch (JSONException ignored) {
                }
            }
            throw e instanceof IOException ? (IOException) e : new IOException("Cannot read the alpha list");
        }
    }

    private static String html(String s) {
        return s.replaceAll("<[^>]+>", "").replace("&#039;", "'").replace("&quot;", "\"").replace("&lt;", "<")
                .replace("&gt;", ">").replace("&amp;", "&").trim();
    }

    /** The commit an alpha was built from: its build run if known, else the run with its title nearest before its date. */
    static String alphaCommit(String runId, String title, String date) throws IOException {
        try {
            if (runId != null && !runId.isEmpty())
                return new JSONObject(Net.getString(RUN + runId)).getString("head_sha");
            String best = null;
            for (int page = 1; page <= 10 && best == null; page++) {
                JSONArray runs = new JSONObject(Net.getString(RUNS + "?status=success&per_page=100&page=" + page)).getJSONArray("workflow_runs");
                for (int i = 0; i < runs.length(); i++) {
                    JSONObject run = runs.getJSONObject(i);
                    // Runs are newest first; the first one with the title that started before the patch was published.
                    if (title.equals(run.optString("display_title"))
                            && (date == null || date.isEmpty() || run.optString("created_at").compareTo(date) <= 0)) {
                        best = run.getString("head_sha");
                        break;
                    }
                }
                if (runs.length() < 100)
                    break;
            }
            if (best == null)
                throw new IOException("No KeeperFX build run matches \"" + title + "\"");
            return best;
        } catch (JSONException e) {
            throw new IOException("Unexpected reply from GitHub: " + e.getMessage());
        }
    }

    /** Source of a commit or tag, reduced to what the engine build reads: src/, deps headers, version files, icon. */
    static File fetch(Context c, String ref, File into, Net.Progress progress) throws IOException {
        File zip = new File(Paths.downloads(c), "keeperfx-src-" + ref.replaceAll("[^A-Za-z0-9.]", "_") + ".zip");
        Net.download(SOURCE_ZIP + ref, zip, -1, progress);
        Prepare.deleteTree(into);
        if (!into.mkdirs())
            throw new IOException("Cannot create " + into);
        String base = into.getCanonicalPath() + File.separator;
        int files = 0;
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip.toPath()))) {
            byte[] buffer = new byte[1 << 16];
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                String name = e.getName();
                int slash = name.indexOf('/');
                if (e.isDirectory() || slash < 0)
                    continue;
                String rel = name.substring(slash + 1);
                if (!wanted(rel))
                    continue;
                File out = new File(into, rel);
                if (!out.getCanonicalPath().startsWith(base))
                    throw new IOException("Unsafe path in source archive: " + name);
                out.getParentFile().mkdirs();
                try (OutputStream o = new FileOutputStream(out)) {
                    for (int n; (n = in.read(buffer)) > 0; )
                        o.write(buffer, 0, n);
                }
                files++;
            }
        }
        if (!new File(into, "src").isDirectory() || !new File(into, "version.mk").isFile())
            throw new IOException("The downloaded KeeperFX source is incomplete (" + files + " files)");
        zip.delete();
        return into;
    }

    static boolean wanted(String rel) {
        return rel.startsWith("src/") || rel.startsWith("deps/centitoml/") || rel.startsWith("deps/glad/")
                || (rel.startsWith("deps/") && rel.indexOf('/', 5) < 0 && rel.endsWith(".h"))
                || rel.equals("version.mk") || rel.equals("ver_defs.h.in") || rel.equals("res/keeperfx_icon256-24bpp.png");
    }

    static String read(InputStream in) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[1 << 16];
        for (int n; (n = in.read(buffer)) > 0; )
            out.write(buffer, 0, n);
        return out.toString("UTF-8");
    }
}
