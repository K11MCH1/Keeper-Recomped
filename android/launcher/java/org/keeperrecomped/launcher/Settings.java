package org.keeperrecomped.launcher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every launcher setting, defined once: the WebView renders the Graphics, Gameplay
 * and Advanced tabs from {@link #schema()}, and {@link ConfigWriter} / {@link GameLauncher}
 * turn the stored values into keeperfx.cfg keys, engine arguments and intent extras.
 * Labels follow the KeeperFX launcher's own settings dialog.
 */
final class Settings {
    enum Type { BOOL, INT, TEXT, CHOICE }

    static final class Def {
        final String tab, section, key, label, help;
        final Type type;
        final String def;
        final String[] options; // value|label pairs for CHOICE
        final int min, max;

        Def(String tab, String section, String key, Type type, String def, String label, String help,
            String[] options, int min, int max) {
            this.tab = tab; this.section = section; this.key = key; this.type = type; this.def = def;
            this.label = label; this.help = help; this.options = options; this.min = min; this.max = max;
        }
    }

    private static final List<Def> DEFS = new ArrayList<>();
    private static final Map<String, Def> BY_KEY = new LinkedHashMap<>();

    private static void bool(String tab, String section, String key, boolean def, String label, String help) {
        add(new Def(tab, section, key, Type.BOOL, def ? "1" : "0", label, help, null, 0, 1));
    }

    private static void integer(String tab, String section, String key, int def, int min, int max, String label, String help) {
        add(new Def(tab, section, key, Type.INT, Integer.toString(def), label, help, null, min, max));
    }

    private static void text(String tab, String section, String key, String def, String label, String help) {
        add(new Def(tab, section, key, Type.TEXT, def, label, help, null, 0, 0));
    }

    private static void choice(String tab, String section, String key, String def, String label, String help, String... options) {
        add(new Def(tab, section, key, Type.CHOICE, def, label, help, options, 0, 0));
    }

    private static void add(Def d) {
        DEFS.add(d);
        BY_KEY.put(d.key, d);
    }

    static {
        // Graphics
        choice("graphics", "Display", "renderer", "vulkan", "Rendering Backend",
                "How finished frames reach the screen.", "vulkan|Vulkan", "gles|OpenGL ES");
        choice("graphics", "Display", "fps_cap", "0", "Max. FPS",
                "Presentation cap. Display maximum follows the screen's highest refresh rate.",
                "0|Display maximum", "60|60", "90|90", "120|120", "144|144", "165|165");
        bool("graphics", "Display", "vsync", true, "Enable VSync", "Present in step with the display; prevents tearing.");
        choice("graphics", "Display", "render_scale", "0", "Resolution",
                "Internal rendering size, scaled to fill the screen. Auto keeps the screen's shape at up to 1280 pixels "
                        + "wide on any device; 100% draws every screen pixel (sharper, heavier).",
                "0|Auto", "1.0|100% (native)", "0.75|75%", "0.5|50%");
        choice("graphics", "Display", "viewport_mode", "ORIGINAL", "Viewport mode",
                "How the in-game view shares the screen with the sidebar.",
                "ORIGINAL|Original", "FULL|Full", "FULL_LETTERBOX|Full (letterbox)");
        choice("graphics", "Display", "resize_movies", "FIT", "Resize Movies", null,
                "FIT|Fit", "STRETCH|Stretch", "CROP|Crop", "PIXELPERFECT|Pixel perfect", "4BY3|4:3", "4BY3PP|4:3 pixel perfect", "OFF|Off");
        bool("graphics", "Display", "show_fps", false, "Show FPS counter", "Android overlay with the measured frame rate.");
        integer("graphics", "Interface", "creature_status_size", 16, 4, 64, "Creature flower size (px)", null);
        integer("graphics", "Interface", "hand_size", 100, 25, 400, "Hand size (%)", null);
        integer("graphics", "Interface", "line_box_size", 150, 25, 400, "Select box line size (%)", null);
        integer("graphics", "Interface", "gui_blink_rate", 1, 0, 10, "GUI blink rate", "Default 1.");
        integer("graphics", "Interface", "neutral_flash_rate", 1, 0, 10, "Neutral flash rate", "Default 1.");
        bool("graphics", "Interface", "parchment_map_fade", true, "Enable map fade animation", null);

        // Gameplay
        choice("gameplay", "Game", "language", "ENG", "Game language", null,
                "ENG|English", "CHI|Chinese (Simplified)", "CHT|Chinese (Traditional)", "CZE|Czech", "DUT|Dutch",
                "FRE|French", "GER|German", "ITA|Italian", "JPN|Japanese", "KOR|Korean", "LAT|Latin", "POL|Polish",
                "POR|Portuguese", "RUS|Russian", "SPA|Spanish", "SWE|Swedish", "UKR|Ukrainian");
        bool("gameplay", "Game", "play_intro", true, "Play intro", "The intro movie on start-up.");
        bool("gameplay", "Game", "play_splash", true, "Play splash screens", "Legal and KeeperFX splash screens.");
        bool("gameplay", "Game", "pause_on_minimize", true, "Pause game when minimized",
                "Freeze the game while another app is in front.");
        bool("gameplay", "Game", "cheats", false, "Enable Cheats",
                "Engine -alex option. Cheat menu and console commands need a keyboard for now.");
        bool("gameplay", "Game", "censorship", false, "Enable Censorship", null);
        bool("gameplay", "Game", "auto_flee", false, "Automatically enable flee", null);
        bool("gameplay", "Game", "auto_imprison", false, "Automatically enable imprison", null);
        integer("gameplay", "Game", "max_zoom", 60, 0, 120, "Max. zoom distance", "Default 60.");
        integer("gameplay", "Game", "turns_per_second", 20, 1, 200, "Gameturns per second", "Default 20.");
        bool("gameplay", "Game", "delta_time", true, "Enable delta time", "Smooth motion between game turns.");
        choice("gameplay", "Game", "screenshot", "PNG", "Screenshot type", null, "PNG|PNG", "BMP|BMP");
        bool("gameplay", "Game", "exit_on_lua_error", false, "Exit on LUA error", null);
        text("gameplay", "Game", "launch_options", "", "Game launch options", "Extra engine command-line options.");
        bool("gameplay", "Replays", "automatic_replays", true, "Automatically save replays", null);
        integer("gameplay", "Replays", "max_replays_campaign", 5, 0, 100, "Campaign max. replays", null);
        integer("gameplay", "Replays", "max_replays_freeplay", 5, 0, 100, "Freeplay max. replays", null);
        integer("gameplay", "Replays", "max_replays_multiplayer", 10, 0, 100, "Multiplayer max. replays", null);
        integer("gameplay", "Replays", "replay_max_size", 32768, 0, 1048576, "Replay max. filesize (KiB)", "0 = unlimited.");
        bool("gameplay", "Sound", "sound", true, "Enable sound", null);
        bool("gameplay", "Sound", "atmospheric_sounds", true, "Enable atmospheric sounds", null);
        choice("gameplay", "Sound", "atmos_volume", "MEDIUM", "Atmosphere volume", null, "LOW|Low", "MEDIUM|Medium", "HIGH|High");
        choice("gameplay", "Sound", "atmos_frequency", "MEDIUM", "Atmosphere frequency", null, "LOW|Low", "MEDIUM|Medium", "HIGH|High");
        bool("gameplay", "Sound", "pause_music_when_paused", false, "Pause music when game is paused", null);
        bool("gameplay", "Sound", "mute_when_unfocused", true, "Mute audio when game is not focused", null);

        // Advanced: Multiplayer, API and Launcher, as in the KeeperFX launcher's tabs
        bool("advanced", "Multiplayer", "matchmaking", true, "Enable matchmaking", null);
        text("advanced", "Multiplayer", "matchmaking_server", "matchmaking.keeperfx.workers.dev", "Matchmaking server", null);
        integer("advanced", "Multiplayer", "multiplayer_port", 5556, 1, 65535, "Multiplayer port", null);
        bool("advanced", "API", "api_enabled", false, "Enable API", "Local scripting API for tools.");
        integer("advanced", "API", "api_port", 5599, 1, 65535, "API port", null);
        choice("advanced", "Launcher", "release_channel", "ALPHA", "KeeperFX Release Channel",
                "Alpha adds the latest alpha patch on top of the stable release.", "STABLE|Stable", "ALPHA|Alpha");
        bool("advanced", "Launcher", "check_updates", true, "Automatically check for KeeperFX updates", null);
        integer("advanced", "Launcher", "update_interval_days", 0, 0, 365, "Update check interval in days", "0 = always check.");
        bool("advanced", "Launcher", "auto_update", false, "Update automatically", "Apply new alpha patches when the launcher opens.");
        bool("advanced", "Launcher", "backup_saves", true, "Backup savefiles during update", null);
        bool("advanced", "Launcher", "website_integration", true, "Enable website integration", "Fetch News and Workshop from keeperfx.net.");
        bool("advanced", "Launcher", "keep_downloads", false, "Keep downloaded archives", "Otherwise they are deleted after installing.");
        choice("advanced", "Launcher", "download_server", "keeperfx.net", "Download Server", null, "keeperfx.net|keeperfx.net");
        integer("advanced", "Touch tuning", "touch_rotate_deadzone_deg", 12, 2, 45, "Rotate dead zone (degrees)",
                "Twist needed before a two-finger gesture rotates, so pinching does not.");
        integer("advanced", "Touch tuning", "touch_pinch_deadzone_pct", 6, 1, 30, "Pinch dead zone (%)",
                "Spread change needed before a two-finger gesture zooms.");
    }

    private final SharedPreferences prefs;

    Settings(Context context) {
        prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    String get(String key) {
        Def d = BY_KEY.get(key);
        if (d == null)
            throw new IllegalArgumentException("Unknown setting " + key);
        return prefs.getString(key, d.def);
    }

    boolean bool(String key) { return "1".equals(get(key)); }

    int integer(String key) {
        try {
            return Integer.parseInt(get(key).trim());
        } catch (NumberFormatException e) {
            return Integer.parseInt(BY_KEY.get(key).def);
        }
    }

    /** @return null when accepted, else why not */
    String set(String key, String value) {
        Def d = BY_KEY.get(key);
        if (d == null)
            return "Unknown setting";
        value = value == null ? "" : value.trim();
        switch (d.type) {
            case BOOL:
                value = ("1".equals(value) || "true".equals(value)) ? "1" : "0";
                break;
            case INT:
                try {
                    int v = Integer.parseInt(value);
                    if (v < d.min || v > d.max)
                        return d.label + ": " + d.min + "-" + d.max;
                    value = Integer.toString(v);
                } catch (NumberFormatException e) {
                    return d.label + ": a whole number";
                }
                break;
            case CHOICE:
                boolean known = false;
                for (String o : d.options)
                    known |= o.substring(0, o.indexOf('|')).equals(value);
                if (!known)
                    return d.label + ": unknown option";
                break;
            case TEXT:
                if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
                    return d.label + ": one line";
                break;
        }
        prefs.edit().putString(key, value).apply();
        return null;
    }

    void resetAll() { prefs.edit().clear().apply(); }

    /** Definitions and current values for the WebView. */
    JSONObject schema() throws JSONException {
        JSONArray defs = new JSONArray();
        for (Def d : DEFS) {
            JSONObject o = new JSONObject();
            o.put("tab", d.tab).put("section", d.section).put("key", d.key).put("type", d.type.name().toLowerCase())
                    .put("label", d.label).put("value", get(d.key)).put("default", d.def);
            if (d.help != null)
                o.put("help", d.help);
            if (d.type == Type.INT)
                o.put("min", d.min).put("max", d.max);
            if (d.options != null) {
                JSONArray opts = new JSONArray();
                for (String opt : d.options) {
                    int bar = opt.indexOf('|');
                    opts.put(new JSONArray().put(opt.substring(0, bar)).put(opt.substring(bar + 1)));
                }
                o.put("options", opts);
            }
            defs.put(o);
        }
        return new JSONObject().put("settings", defs);
    }
}
