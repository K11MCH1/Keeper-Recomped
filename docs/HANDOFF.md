# Handoff — Claude (launcher, packaging)

Newest block first. Evidence = command + observed output. Codex's state lives in `coordination/CODEX.md`.

## CURRENT — 2026-10-29 05:30 KST

**Working on the phone (NX809J, Android 16), debug APK `org.keeperrecomped.app`:**
| Feature | Evidence |
|---|---|
| Launcher UI, 8 tabs, edge-to-edge, back gesture | screenshots `$TEMP/kr-s4.png`, `kr-tabs.png` |
| Prepare: KeeperFX 1.4.0 complete (375 MB) download + on-device 7z extract, 20 DK files, alpha 1.4.0.5571, keeperfx.cfg | `run-as org.keeperrecomped.app cat files/state.json` → `kfx_version 1.4.0`, `alpha_version 1.4.0.5571`, `prepared true`; `du -sh files/kfx` = 602M; ~90 s |
| News (keeperfx.net API) | News tab screenshot shows 1.4.0 article |
| Workshop list + install (API files empty → item-page link fallback) | `files/kfx/levels/standard/map27194.*` installed for item 870 |
| Update task (no-op when current; reinstalls stable when newer or when switching Alpha→Stable) | `files/logs/launcher.log` "step Writing settings" |
| Play KeeperFX: intro → main menu (Vulkan, 1280x579 internal, full screen) → Campaigns → Original Campaign → realm map | screenshots `$TEMP/kr-p2s.png`, `kr-p10s.png`; 0 presenter errors after Codex's reset fix |
| Quit from main menu ends the `:game` process, launcher returns | `pidof org.keeperrecomped.app:game` empty |
| Play Original = classic root `files/original` (symlinks, only keeporig*, `-nomods`, own saves) | launcher.log "classic root linked in 75 ms"; `du` 34K; Campaigns → realm map directly |
| GLES fallback + show_fps | keeperfx.log "Presenting through SDL renderer: opengles2"; "FPS 60.2" overlay |

**Fixed (Claude takeover, cb20f94):** level start (Lua loader exact-then-lowercase) and music (absolute runtime directory). Level 1 of the original campaign loads in the main APK; music plays; real one-finger drag pans.

**Also fixed (Claude takeover):** MP3 briefing speech (sdl3-mixer overlay port with dr_mp3, 4fedcfb); Android Back = Escape (8e1249b, engine-log verified; in-level Options not re-checked because the phone re-locked).

**Open:** thin campaign-list hit boxes (Codex, minor); in-level Back→Options visual check; SAF picker needs a real pick by the user. RedMagic **Game Space** auto-added the app (it is flagged as a game) and may cap its frame rate — check Game Space's per-game frame/refresh setting along with the display refresh setting.

**FPS (OS-level, fixed `scripts/android/measure_fps.py`, main APK, level 1):** 60.2 FPS, p99 16.62 ms, max 16.63 ms — perfectly paced at the panel's current 60 Hz. The OS display policy decides the panel rate (`dumpsys SurfaceFlinger | grep displayManagerPolicy`: [60,60] now, [120,120] earlier); the game requests 144 Hz and the launcher writes FRAMES_PER_SECOND=144. 144 Hz needs the user's display setting (refresh_rate_mode auto → 144).

**Not yet verified:** SAF folder picker import on a real pick (shell cannot grant tree URIs; needs the user to pick `Download/Dungeon Keeper/DATA` once — the files are already on the phone there), importing a PC KeeperFX folder, level start/gestures/FPS in the main APK (after Codex fixes the blockers; Codex verified gestures + 120 FPS on its own lowercase test tree).

**Test-environment facts:**
- Phone has **no internet** (Wi-Fi off, cellular IMS only). Debug builds use `python scripts/android/dev_relay.py` (background) + `adb -s 9125335101BA reverse tcp:8765 tcp:8765` + launch extra `--es debug_relay http://127.0.0.1:8765` (persisted in prefs).
- ROM sets `log.tag=S`: logcat is empty for apps. Read `adb shell run-as org.keeperrecomped.app tail files/logs/launcher.log` and `files/kfx/keeperfx.log`.
- Phone is usually on keyguard. Debug-only `--ez debug_show_when_locked true` shows launcher (and game, forwarded) above it without unlocking. Wake: `adb shell input keyevent KEYCODE_WAKEUP`.
- Drive the page: `--es debug_action tab:<name>|prepare|update|play:keeperfx|play:original|cancel`.
- In Git Bash prefix device-path adb commands with `MSYS_NO_PATHCONV=1`, but give `adb install` a relative/Windows path.
- DK test data: `/sdcard/Download/Dungeon Keeper/DATA` (for the real picker) and `/data/local/tmp/kr-dk` + `/data/local/tmp/kr-stage.sh` (run-as staging into `files/dk`).

**Next for Claude:** retest game after Codex's present fix (menus, tap/hold, two-finger slap, pinch/rotate, FPS via `dumpsys SurfaceFlinger --latency`); propose/verify a cheat-menu trigger; README for the user; release (non-debug) build check.

**Commands:**
```
python scripts/android/build_apk.py --output build/apk/keeper-recomped.apk --debuggable
adb -s 9125335101BA install -r build/apk/keeper-recomped.apk
MSYS_NO_PATHCONV=1 adb -s 9125335101BA shell am start -n org.keeperrecomped.app/org.keeperrecomped.launcher.LauncherActivity --ez debug_show_when_locked true --es debug_relay http://127.0.0.1:8765 --es debug_action play:keeperfx
```
