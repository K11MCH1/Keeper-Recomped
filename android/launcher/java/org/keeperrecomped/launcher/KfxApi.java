package org.keeperrecomped.launcher;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * keeperfx.net API, the same endpoints the KeeperFX launcher uses
 * (https://github.com/dkfans/keeperfx-website). Responses are cached so the
 * News and Workshop tabs still show something offline.
 */
final class KfxApi {
    static final String BASE = "https://keeperfx.net/api/v1/";

    private final File cache;

    KfxApi(Context context) {
        cache = Paths.apiCache(context);
    }

    /** Fresh response, falling back to the cached copy when offline. */
    String get(String endpoint) throws IOException {
        File file = new File(cache, endpoint.replaceAll("[^A-Za-z0-9._-]", "_") + ".json");
        try {
            String body = Net.getString(BASE + endpoint);
            new JSONObject(body); // only cache valid JSON
            cache.mkdirs();
            Files.write(file.toPath(), body.getBytes(StandardCharsets.UTF_8));
            return body;
        } catch (IOException | JSONException e) {
            if (file.isFile()) {
                try {
                    JSONObject cached = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                    cached.put("_offline", true);
                    return cached.toString();
                } catch (JSONException ignored) {
                }
            }
            throw e instanceof IOException ? (IOException) e : new IOException("Invalid response from " + endpoint);
        }
    }

    JSONObject json(String endpoint) throws IOException {
        try {
            return new JSONObject(get(endpoint));
        } catch (JSONException e) {
            throw new IOException("Invalid response from " + endpoint);
        }
    }

    /** {name, version, tag, download_url, size_in_bytes} of the latest stable "complete" release. */
    JSONObject stable() throws IOException {
        JSONObject release = json("release/stable/latest").optJSONObject("release");
        if (release == null || release.optString("download_url").isEmpty())
            throw new IOException("keeperfx.net returned no stable release");
        return release;
    }

    /**
     * Downloadable files of a workshop item. The API's files list is empty for most
     * items, so fall back to the download links on the item's web page.
     */
    org.json.JSONArray workshopFiles(String id, String pageUrl) throws IOException {
        org.json.JSONArray files = new org.json.JSONArray();
        try {
            JSONObject item = json("workshop/item/" + id).optJSONObject("workshop_item");
            org.json.JSONArray api = item == null ? null : item.optJSONArray("files");
            if (api != null && api.length() > 0)
                return api;
        } catch (IOException e) {
            // fall through to the page
        }
        if (!pageUrl.startsWith("https://keeperfx.net/workshop/item/" + id))
            throw new IOException("Unexpected workshop page " + pageUrl);
        String html = Net.getString(pageUrl);
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("https://keeperfx\\.net/workshop/download/" + id + "/(\\d+)/([^\"'<>\\s]+)").matcher(html);
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (m.find()) {
            if (!seen.add(m.group(1)))
                continue;
            String name;
            try {
                name = java.net.URLDecoder.decode(m.group(2), "UTF-8");
            } catch (IllegalArgumentException e) {
                name = m.group(2);
            }
            try {
                files.put(new JSONObject().put("id", Long.parseLong(m.group(1))).put("filename", name)
                        .put("url", m.group(0)).put("size", -1));
            } catch (JSONException ignored) {
            }
        }
        return files;
    }

    /** {name, version, filename, download_url, size_in_bytes, workflow_title} of the latest alpha patch. */
    JSONObject alpha() throws IOException {
        JSONObject build = json("release/alpha/latest").optJSONObject("alpha_build");
        if (build == null || build.optString("download_url").isEmpty())
            throw new IOException("keeperfx.net returned no alpha build");
        return build;
    }
}
