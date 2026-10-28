package org.keeperrecomped.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import org.json.JSONObject;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the engine (Codex's org.keeperrecomped.game.GameActivity) with the launch
 * contract in coordination/CLAUDE.md: game_root, profile, args, renderer, fps_cap,
 * render_scale, touch dead zones, show_fps, original_lib.
 */
final class GameLauncher {
    static final String GAME_ACTIVITY = "org.keeperrecomped.game.GameActivity";

    private GameLauncher() {}

    static boolean engineInstalled(Context c) {
        try {
            c.getPackageManager().getActivityInfo(new ComponentName(c, GAME_ACTIVITY), 0);
            Class.forName(GAME_ACTIVITY);
            return true;
        } catch (PackageManager.NameNotFoundException | ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * The original recompilation produced by the build hook, if any: the hook writes
     * files/build/original.json = {"lib": "/abs/libX.so", "game_root": "/abs/dir"}.
     */
    static JSONObject originalBuild(Context c) {
        File f = new File(Paths.build(c), "original.json");
        if (!f.isFile())
            return null;
        try {
            JSONObject o = new JSONObject(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8));
            return new File(o.optString("lib")).isFile() ? o : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** @return null when started, else a message for the player */
    static String play(Context c, String profile, boolean debugShowWhenLocked) {
        if (!engineInstalled(c))
            return "This build does not contain the game engine yet.";
        if (PrepareService.isRunning())
            return "Wait for the current task to finish.";
        Settings s = new Settings(c);
        File kfx = Paths.kfx(c);
        if (!new File(kfx, "fxdata").isDirectory() || !new File(kfx, "data/bluepal.dat").isFile())
            return "Press Prepare & Build first.";
        try {
            Prepare.fromTemplates(kfx);
            ConfigWriter.write(kfx, s); // settings may have changed since preparing
        } catch (Exception e) {
            return "Cannot write keeperfx.cfg: " + e.getMessage();
        }

        List<String> args = new ArrayList<>();
        if (s.bool("cheats"))
            args.add("-alex");
        if (!s.bool("sound"))
            args.add("-nosound");
        String root = kfx.getPath();
        String originalLib = null;
        if ("original".equals(profile)) {
            JSONObject build = originalBuild(c);
            if (build != null) {
                originalLib = build.optString("lib");
                root = build.optString("game_root", Paths.dk(c).getPath());
            } else {
                // Classic profile on the KeeperFX engine: its own root with only the
                // original campaign, no mods and separate saves (see ClassicRoot).
                try {
                    File original = Prepare.syncClassic(c);
                    ConfigWriter.write(original, s);
                    root = original.getPath();
                } catch (Exception e) {
                    return "Cannot set up the original game: " + e.getMessage();
                }
                args.add("-nomods");
            }
        }
        for (String a : s.get("launch_options").trim().split("\\s+"))
            if (!a.isEmpty())
                args.add(a);

        Intent i = new Intent().setComponent(new ComponentName(c, GAME_ACTIVITY));
        i.putExtra("game_root", root);
        i.putExtra("profile", "original".equals(profile) ? "original" : "keeperfx");
        i.putExtra("args", args.toArray(new String[0]));
        i.putExtra("renderer", s.get("renderer"));
        i.putExtra("fps_cap", s.integer("fps_cap"));
        i.putExtra("vsync", s.bool("vsync"));
        float scale = 0f;
        try {
            scale = Float.parseFloat(s.get("render_scale"));
        } catch (NumberFormatException ignored) {
        }
        i.putExtra("render_scale", scale);
        i.putExtra("touch_rotate_deadzone_deg", (float) s.integer("touch_rotate_deadzone_deg"));
        i.putExtra("touch_pinch_deadzone", s.integer("touch_pinch_deadzone_pct") / 100f);
        i.putExtra("show_fps", s.bool("show_fps"));
        if (originalLib != null)
            i.putExtra("original_lib", originalLib);
        if (debugShowWhenLocked)
            i.putExtra("debug_show_when_locked", true); // engineering builds; see GameActivity
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        AppLog.i("play " + profile + " root=" + root + " args=" + args);
        try {
            c.startActivity(i);
        } catch (Exception e) {
            return "Cannot start the game: " + e.getMessage();
        }
        return null;
    }
}
