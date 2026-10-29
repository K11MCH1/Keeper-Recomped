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

**FPS at 144 Hz (2026-10-29 08:40 KST, after the user set 144 Hz for the game in Game Space):** policy [144,144] while the game is in front (60 at the home screen). Main menu 144.6 FPS; level 1 idle 144.6 FPS; level 1 while panning by drag 144.6 FPS — p50/p99 6.92 ms, max 6.94 ms, no gaps. The engine's own counter (`AndroidPerformance present_fps` in keeperfx.log) agrees: ≈144.0 on Vulkan, so these are new frames, not repeats.

**Touch additions (2026-10-29 09:00–09:55 KST, Claude takeover of the touch layer; user request):** verified on the phone in level 1 with the gesture injector (`build_apk.py --debuggable --touch-probe`, launch extras `enable_touch_probe`/`input_diagnostics`, `am broadcast -a org.keeperrecomped.app.DEBUG_TOUCH --es scenario tracks --es tracks "L:x0,y0,x1,y1,t0,t1;..."`, camera/hand/possession state from `AndroidInput snapshot`/`AndroidPossess` lines in keeperfx.log):
- Map keeps zoom and rotation: pinch+twist to zoom 14303 / yaw 498, then map → tap a place, and map → two-finger tap: both back at zoom 14303 / yaw 498 (only position moves).
- Creature-panel hold: holding the idle-imp count had all 3 imps in hand (hand=3) before the finger lifted.
- Double tap and hold drags a dig box (screenshot mid-drag shows the box; on release the whole box is tagged; the player's highlight mode is restored). Single tap tags one tile.
- Possession: stick moves the creature (position changed ~820 units), right-side drag turns (yaw 198→312→136), attack tap fires the active instance (instance=39 after release), panel taps select spells (active_instance 39↔1) and the bottom arrow turns the page, ✕ leaves possession.
- Fixed on the way: taps were press+release in one frame, which tags no dig tile in a level (digging needs a held press); taps are now short real clicks (≥1 game turn) except over a creature, where the instant click picks it up before it walks away. Injected keys are held 120 ms (the level samples keys per game turn). Diagnostics-only crash: the input snapshot called `map_to_screen` before the first 3D frame (`rotpers` null); guarded. Crash logs now print the return address (`Context LR=... symbol`).

**On-phone engine builds, version picker, rebuild warning (2026-10-29 10:40–11:10 KST, Claude; user request):** the launcher compiles the KeeperFX engine on the phone (Termux Clang 21/LLD in the APK, as in the Badman launcher) against `libkrdeps.so` (all version-independent libraries prelinked on the PC). Verified on the NX809J:
- By hand over adb first: 351 units of alpha 5573 compiled in 19 s on 8 cores at -O2, linked in under 1 s (6.3 MB `libkeeperfx.so`).
- In the app (`--es debug_action build`): resolve commit (keeperfx.net run id → GitHub run → `24e8192d`) 2 s, source download 3 s, compile 22 s, link <1 s; the game then logs `Dungeon Keeper FX ver 1.4.0.5573 Android ARM64 … git:24e8192d…+android`, reaches the main menu (Back works, so JNI binds through `System.load`) and level 1.
- `--es debug_action version:1.4.0.5572`: switched data in ~45 s (same 1142-file set, nothing removed, no stable download), card showed the mismatch warning and highlighted Rebuild game; rebuild resolved the older alpha by title/date to `d3a6350a` (= 5572) and built in 22 s; the game logged ver 1.4.0.5572. Update + rebuild restored 5573 (unpinned).
- After installing an app update with changed `native/src`, the card showed "This app update changes the game engine"; rebuild cleared it.
- Refresh rate: the first launch after an install used to run at 60 Hz because the engine requested a fixed 60 fps surface rate (SDL listed only the current mode). GameActivity now passes `--display-max-hz`; first launch measured 144.8 FPS (`requested_refresh=144.00`, policy [144,144]).
- Not tested on the phone: switching to the stable release (357 MB download) and the "cannot be built" message (stable 1.4.0 applies only 18 of 35 seams: it predates the SDL3 renderer); the engine step inside a first-time Prepare & Build.
- Version facts: alpha N → commit via its signed-alpha build run; the bundled engine commit `da038f03a` is not a published alpha (it lies between 5571 = `b045ce90` and 5572), so the "5571" label of the bundled engine is approximate.

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
