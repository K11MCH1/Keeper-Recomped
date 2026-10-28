package org.keeperrecomped.launcher;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes keeperfx.cfg in a game root from the launcher settings. The existing
 * file (or KeeperFX's own _keeperfx.cfg template) is kept line for line: only
 * the keys below are replaced, so comments and keys we do not manage survive.
 */
final class ConfigWriter {
    private ConfigWriter() {}

    static Map<String, String> values(Settings s) {
        Map<String, String> v = new LinkedHashMap<>();
        v.put("INSTALL_PATH", "./");
        v.put("LANGUAGE", s.get("language"));
        // The Android window is always the whole screen; the engine scales to it.
        v.put("FRONTEND_RES", "DESKTOP DESKTOP DESKTOP");
        v.put("INGAME_RES", "DESKTOP");
        v.put("DISPLAY_NUMBER", "1");
        v.put("VSYNC", onOff(s.bool("vsync")));
        // KeeperFX draws into its own frame buffer; Vulkan/GLES presentation is the
        // Android layer's job (intent extra "renderer").
        v.put("RENDERER", "SOFTWARE");
        v.put("VIEWPORT_MODE", s.get("viewport_mode"));
        v.put("RESIZE_MOVIES", s.get("resize_movies"));
        v.put("FREEZE_GAME_ON_FOCUS_LOST", onOff(s.bool("pause_on_minimize")));
        v.put("PAUSE_MUSIC_WHEN_GAME_PAUSED", onOff(s.bool("pause_music_when_paused")));
        v.put("MUTE_AUDIO_ON_FOCUS_LOST", onOff(s.bool("mute_when_unfocused")));
        v.put("ATMOSPHERIC_SOUNDS", onOff(s.bool("atmospheric_sounds")));
        v.put("ATMOS_VOLUME", s.get("atmos_volume"));
        v.put("ATMOS_FREQUENCY", s.get("atmos_frequency"));
        v.put("MUSIC_FROM_DISK", "ON");
        // Touch: absolute pointer, no capture, no edge panning under a resting finger.
        v.put("CAPTURE_CURSOR", "OFF");
        v.put("RELATIVE_MOUSE_MODE", "OFF");
        v.put("CURSOR_EDGE_CAMERA_PANNING", "OFF");
        v.put("POINTER_SENSITIVITY", "0");
        v.put("SCREENSHOT", s.get("screenshot"));
        v.put("DELTA_TIME", onOff(s.bool("delta_time")));
        v.put("TURNS_PER_SECOND", Integer.toString(s.integer("turns_per_second")));
        int fps = s.integer("fps_cap");
        // Not AUTO: the engine would lock to whatever refresh the display reports at
        // start-up (often 60 Hz on Android), and the OS then keeps the panel at 60.
        int cap = fps > 0 ? fps : s.displayMaxHz;
        v.put("FRAMES_PER_SECOND", cap > 0 ? Integer.toString(cap) : "0");
        v.put("MAX_ZOOM_DISTANCE", Integer.toString(s.integer("max_zoom")));
        v.put("PARCHMENT_MAP_FADE", onOff(s.bool("parchment_map_fade")));
        v.put("CENSORSHIP", onOff(s.bool("censorship")));
        v.put("FLEE_BUTTON_DEFAULT", onOff(s.bool("auto_flee")));
        v.put("IMPRISON_BUTTON_DEFAULT", onOff(s.bool("auto_imprison")));
        StringBuilder startup = new StringBuilder();
        if (s.bool("play_splash"))
            startup.append("LEGAL FX");
        if (s.bool("play_intro"))
            startup.append(startup.length() > 0 ? " " : "").append("INTRO");
        v.put("STARTUP", startup.toString());
        v.put("CREATURE_STATUS_SIZE", Integer.toString(s.integer("creature_status_size")));
        v.put("HAND_SIZE", Integer.toString(s.integer("hand_size")));
        v.put("LINE_BOX_SIZE", Integer.toString(s.integer("line_box_size")));
        v.put("GUI_BLINK_RATE", Integer.toString(s.integer("gui_blink_rate")));
        v.put("NEUTRAL_FLASH_RATE", Integer.toString(s.integer("neutral_flash_rate")));
        v.put("AUTOMATIC_REPLAYS", onOff(s.bool("automatic_replays")));
        v.put("MAX_REPLAYS", s.integer("max_replays_campaign") + " " + s.integer("max_replays_freeplay") + " "
                + s.integer("max_replays_multiplayer"));
        v.put("REPLAY_MAX_SIZE", Integer.toString(s.integer("replay_max_size")));
        v.put("API_ENABLED", s.bool("api_enabled") ? "TRUE" : "FALSE");
        v.put("API_PORT", Integer.toString(s.integer("api_port")));
        String server = s.get("matchmaking_server");
        v.put("MATCHMAKING_SERVER", s.bool("matchmaking") && !server.isEmpty() ? server : "OFF");
        v.put("MULTIPLAYER_PORT", Integer.toString(s.integer("multiplayer_port")));
        v.put("EXIT_ON_LUA_ERROR", s.bool("exit_on_lua_error") ? "TRUE" : "FALSE");
        return v;
    }

    static void write(File gameRoot, Settings settings) throws IOException {
        File target = new File(gameRoot, "keeperfx.cfg");
        File template = new File(gameRoot, "_keeperfx.cfg");
        List<String> lines = new ArrayList<>();
        if (target.isFile())
            lines.addAll(Files.readAllLines(target.toPath(), StandardCharsets.UTF_8));
        else if (template.isFile())
            lines.addAll(Files.readAllLines(template.toPath(), StandardCharsets.UTF_8));
        Map<String, String> values = values(settings);
        Map<String, Boolean> written = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            int eq = trimmed.indexOf('=');
            if (trimmed.startsWith(";") || eq <= 0)
                continue;
            String key = trimmed.substring(0, eq).trim().toUpperCase();
            if (values.containsKey(key)) {
                lines.set(i, key + "=" + values.get(key));
                written.put(key, true);
            }
        }
        boolean header = false;
        for (Map.Entry<String, String> e : values.entrySet()) {
            if (written.containsKey(e.getKey()))
                continue;
            if (!header) {
                lines.add("");
                lines.add("; Written by the Keeper Recomped launcher");
                header = true;
            }
            lines.add(e.getKey() + "=" + e.getValue());
        }
        File tmp = new File(gameRoot, "keeperfx.cfg.tmp");
        Files.write(tmp.toPath(), (String.join("\r\n", lines) + "\r\n").getBytes(StandardCharsets.UTF_8));
        if (!tmp.renameTo(target))
            throw new IOException("Cannot replace " + target);
    }

    private static String onOff(boolean b) { return b ? "ON" : "OFF"; }
}
