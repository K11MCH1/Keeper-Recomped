package org.keeperrecomped.launcher;

import android.content.Context;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Persistent record of what has been prepared (files/state.json). */
final class State {
    private State() {}

    static synchronized JSONObject load(Context c) {
        File f = Paths.state(c);
        if (f.isFile()) {
            try {
                return new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            } catch (IOException | JSONException ignored) {
            }
        }
        return new JSONObject();
    }

    static synchronized void save(Context c, JSONObject state) throws IOException {
        File f = Paths.state(c);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            Files.write(tmp.toPath(), state.toString(1).getBytes(StandardCharsets.UTF_8));
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (!tmp.renameTo(f))
            throw new IOException("Cannot write " + f);
    }

    static synchronized void put(Context c, String key, Object value) throws IOException {
        JSONObject s = load(c);
        try {
            s.put(key, value);
        } catch (JSONException e) {
            throw new IOException(e);
        }
        save(c, s);
    }
}
