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

    /** {name, version, filename, download_url, size_in_bytes, workflow_title} of the latest alpha patch. */
    JSONObject alpha() throws IOException {
        JSONObject build = json("release/alpha/latest").optJSONObject("alpha_build");
        if (build == null || build.optString("download_url").isEmpty())
            throw new IOException("keeperfx.net returned no alpha build");
        return build;
    }
}
