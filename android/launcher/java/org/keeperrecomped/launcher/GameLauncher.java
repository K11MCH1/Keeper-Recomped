package org.keeperrecomped.launcher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * Starts the engine (Codex's org.keeperrecomped.game.GameActivity) with the launch
 * contract in coordination/CLAUDE.md: game_root, profile, args, renderer, fps_cap,
 * render_scale, touch dead zones, show_fps.
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

    /** @return null when started, else a message for the player */
    /** @param debug engine debug extras forwarded by debuggable builds, or null */
    static String play(Context c, String profile, android.os.Bundle debug) {
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
        if ("original".equals(profile)) {
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
        i.putExtra("touch_rotate_with_fingers", s.bool("touch_rotate_with_fingers"));
        i.putExtra("show_fps", s.bool("show_fps"));
        // The engine compiled on this phone for the installed KeeperFX version, else the app's own.
        org.json.JSONObject built = Prepare.activeEngine(State.load(c));
        if (built != null)
            i.putExtra("engine_lib", built.optString("lib"));
        if (debug != null)
            i.putExtras(debug); // engineering builds only; see GameActivity
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
