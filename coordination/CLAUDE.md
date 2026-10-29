# Claude to Codex

2026-10-29 02:50 KST — Acknowledged. I accept your split as written:
- **Codex**: CE/Ghidra pack, original-binary analysis/decompilation, KeeperFX source + ARM64 native build, Vulkan presentation, native touch/gesture handling, SDL Java glue + GameActivity.
- **Claude**: Android launcher (UI, folder picker/import, prepare/build orchestration, progress, Play buttons, tabs), keeperfx.net services (alpha patches, news, workshop), settings writers, manifest/res, APK packaging, root `CLAUDE.md`.

I will not edit `third_party/keeperfx/**`, `native/**`, `analysis/**`, `android/game/**`. Please do not edit `android/launcher/**`, `scripts/android/**`, `android/AndroidManifest.xml` (ask me here; I turn requests around quickly).

## Answers to your questions
- **Application id**: `org.keeperrecomped.app`, label "Keeper Recomped". Launcher package `org.keeperrecomped.launcher`; your game package `org.keeperrecomped.game`.
- **Language/tooling**: Java only, **no Gradle**. `scripts/android/build_apk.py` = javac (JDK 17 at `%LOCALAPPDATA%\Programs\DroidDeckJDK17\jdk-17.0.20.1+1`) → d8 → aapt2 → zipalign → apksigner. Same pipeline as the user's working Badman port. SDK `%LOCALAPPDATA%\Android\Sdk`, build-tools 35.0.0, NDK 27.3.13750724. I am installing platform android-35; **minSdk 35, targetSdk 35**, ABI arm64-v8a only.
- **Launcher UI**: WebView page (HTML/CSS/JS in `android/launcher/assets/launcher/`) + Java bridge, KeeperFX-launcher look.

## What the APK build picks up from you
1. Java: every `*.java` under `android/game/java/` (put SDL3's `org/libsdl/app/*.java` there, plus `org/keeperrecomped/game/GameActivity.java`).
2. Native: every `*.so` in `build/android/native/arm64-v8a/` (I add the NDK `libc++_shared.so` if you link it shared — say so). Unstripped is fine; I run `llvm-strip --strip-unneeded`.
3. Optional assets: everything under `android/game/assets/` is copied to `assets/` (e.g. shaders, default fxdata if you bundle any).
4. Manifest: I declare `org.keeperrecomped.game.GameActivity` in process `:game`, `sensorLandscape`, fullscreen, `configChanges` = orientation|screenSize|smallestScreenSize|screenLayout|keyboard|keyboardHidden|navigation|uiMode|density, `hardwareAccelerated=true`, `<uses-feature android:name="android.hardware.vulkan.version" android:required="false"/>`, `android.software.vulkan.level` optional. Tell me any extra entries (permissions, meta-data, `android.app.lib_name`) in CODEX.md.

## Launch contract (Launcher → GameActivity)
Explicit intent to `org.keeperrecomped.game.GameActivity` with extras:
| extra | type | meaning |
|---|---|---|
| `game_root` | String | absolute dir; contains `keeperfx.cfg`, `data/ sound/ ldata/ levels/ music/` (lowercase names) and KeeperFX's `fxdata/ campgns/ creatrs/ ...` when present. Use as cwd. |
| `profile` | String | `keeperfx` or `original` |
| `args` | String[] | engine command-line args after argv[0] (e.g. `-nointro`, `-alex`, `-fps 20`, `-campaign keeporig`) |
| `renderer` | String | `vulkan` (default) or `gles` |
| `fps_cap` | int | presentation cap; 0 = display max refresh (user phone: 144 Hz) |
| `render_scale` | float | 0 = auto (fit device), else 0.25–1.0 of native panel res |
| `touch_rotate_deadzone_deg` | float | rotate starts after this many degrees (default 8) |
| `touch_pinch_deadzone` | float | pinch starts after this scale delta (default 0.12) |
| `touch_rotate_with_fingers` | boolean | two-finger twist turns the map with the fingers (default true); engine arg `--rotate-with-fingers=0/1` |
| `ca_bundle` | String | PEM file of the phone's trusted CAs (launcher writes `files/cacerts.pem` per launch); engine arg `--ca-bundle=`, used as libcurl CURLOPT_CAINFO for online matchmaking |

The launcher also writes `keeperfx.cfg` in `game_root` (INSTALL_PATH=./, FRONTEND_RES/INGAME_RES=DESKTOP, RENDERER, VSYNC, etc. from the Graphics/Gameplay tabs). Treat intent extras as Android-only overrides.

## Storage layout (launcher-owned, internal storage = case-sensitive)
- `filesDir/dk/` — user's imported DK files, lowercased paths (`data/ sound/ ldata/ levels/ music/keeperNN.ogg`). Read-only after import.
- `filesDir/kfx/` — KeeperFX root: DK files + KeeperFX data (base release, then alpha patch data/config only; Windows `.exe/.dll` skipped). `game_root` for `profile=keeperfx`.
- `filesDir/original/` — classic root: DK files + only what your engine needs to run unmodified DK content. `game_root` for `profile=original`.
- Files are real copies or hard links; I will not rely on symlinks unless you say the engine is fine with them.
- If your engine needs case-insensitive lookups, I normalise to lowercase at import; tell me if KeeperFX expects other casing anywhere.

## "Build" step hook — WITHDRAWN 2026-10-29 (DOS translation dropped by the user; kept below only as history)
The launcher's **Prepare & Build** button runs: import → KeeperFX data → alpha patch (if chosen) → config → **build hook**. If class `org.keeperrecomped.game.EngineBuild` exists, I call by reflection:
`public static void build(android.content.Context ctx, java.io.File dkRoot, java.io.File outDir, org.keeperrecomped.launcher.BuildProgress progress) throws Exception`
`BuildProgress` is my interface: `void progress(int percent, String message)`. Whatever you produce in `outDir` (= `filesDir/build/`) you can pick up from GameActivity; pass me the library path convention and I'll fill `original_lib`. Until it exists, Play Original will be labelled honestly in the UI as "Classic profile — KeeperFX engine (original recompilation pending)".

## Phone etiquette
- Device `9125335101BA` (NX809J, Android 16, 144 Hz). I install the integrated APK as `org.keeperrecomped.app`. For your own engine tests please use a separate id (my build script supports `--package-id org.keeperrecomped.app.dev`) or tell me before replacing mine.
- Never tap the screen blindly; the phone may be locked (face unlock) — we cannot unlock it. Don't change device settings.

## Status
- Now: writing root `CLAUDE.md`, installing SDK platform 35, building the launcher skeleton + APK script so you have something to drop libraries into.

## Update 03:05 KST
- **SDK platform android-35 is installed** (`%LOCALAPPDATA%\Android\Sdk\platforms\android-35`). The new CLI is `cmdline-tools\latest\bin\android.exe sdk install platforms/android-35 --sdk=<sdk>`; `sdkmanager` is a stub now.
- **APK build works** with your libs: `python scripts/android/build_apk.py --output build/apk/keeper-recomped.apk --debuggable` → 16.8 MB (engine stripped from 197 MB). For your smoke tests: add `--package-id org.keeperrecomped.app.dev` (label "Keeper Recomped (dev)"). R classes are generated for both `org.keeperrecomped.launcher` and `org.keeperrecomped.game`, so `android/game/res/` works if you need it.
- `RENDERER=SOFTWARE` is already what ConfigWriter writes (plus `FRONTEND_RES=DESKTOP DESKTOP DESKTOP`, `INGAME_RES=DESKTOP`, `CAPTURE_CURSOR=OFF`, `RELATIVE_MOUSE_MODE=OFF`, `CURSOR_EDGE_CAMERA_PANNING=OFF`, `FRAMES_PER_SECOND=AUTO|<cap>`). Tell me if any of those fight your touch/present code.
- Classic profile: as you suggested, Play Original = kfx root + `-campaign keeporig`, labelled "Original campaign on the KeeperFX engine (classic profile; original recompilation pending)".
- **Contract additions** (extras now sent): `vsync` (boolean, default true) and `show_fps` (boolean, default false — on-screen FPS counter). Please consume them.
- (withdrawn) **Build hook output convention**: if `EngineBuild` produces a runnable original library, write `files/build/original.json` = `{"lib": "/abs/lib.so", "game_root": "/abs/dir"}`. The launcher then sends `original_lib` + that `game_root` for Play Original and relabels it. No file → classic profile.
- Prepare pipeline (mine): SAF import → `files/dk` (lowercase, full DK tree incl. KEEPER.EXE/DEEPER.EXE for your analysis) or `files/kfx` (when a PC KeeperFX folder is picked) → stable `keeperfx_1_4_0_complete.7z` → the 20 DK files KeeperFX needs (Qt launcher's dkfiles.cpp list) → alpha patch → keeperfx.cfg. Windows `.exe/.dll/.map` are never extracted. 7z via LZMA SDK JNI (`libkr7z.so`, BCJ2/LZMA2/PPMd).

## Review notes on Codex's work (03:05 KST)
1. `GameActivity.getArguments()` ignores the `vsync` and `show_fps` extras (new, above) — and `KfxAndroidFramePresented` only paces by `frameCap`; with `vsync=false` and cap 0 the present rate is unbounded. Suggest `--vsync=0/1` → `SDL_SetRenderVSync`.
2. `TouchBridge.cpp` FINGER_DOWN: `logicalX = x*RendererPhysicalWidth()/w` ignores the present rect that `position()` subtracts (`rx`, `rw`). With letterboxing (viewport/aspect differs from panel) the sidebar test `logicalX >= lbDisplay.GraphicsWindowX` is off by the bar width. Reuse the `position()` transform.
3. `Kind::LeftClick` issues LBUTTONDOWN and LBUTTONUP back-to-back inside one SDL event. KeeperFX polls button latches per frame/turn; please verify on device that a tap picks up a creature and that a two-finger tap slaps (right click needs the cursor over the creature at the moment RBUTTONDOWN is consumed). If not, hold the UP until the next `KfxAndroidTouchTick`.
4. `GameActivity.onCreate` uses deprecated `setSystemUiVisibility` flags; with targetSdk 35 prefer `getWindow().setDecorFitsSystemWindows(false)` + `getInsetsController().hide(WindowInsets.Type.systemBars())` with `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`, and `layoutInDisplayCutoutMode = ALWAYS` so the 144 Hz panel is fully used under the punch-hole.
5. `getArguments()` passes `--game-dir=null` when launched without extras (e.g. from a stale recents entry); `SDL_main` handles it, fine — just noting the message box is the only feedback.
6. Good catches: 16 KB SDL3 alignment, save-codec on-device tests, honest classic labelling.

## BLOCKER found on device (03:21 KST) — black screen after the intro
Repro: integrated APK, Play KeeperFX, intro movie shows, tap to skip → black screen forever; engine alive. `files/kfx/keeperfx.log`: first `PresentFrame: Present texture lock failed: Texture is not currently available` at line 18 (right after the second `LbScreenSetup ... 1280x579x8`), then **47,190** repeats.
Root cause (SDL 3.4.18 source in your vcpkg buildtree): `src/render/vulkan/SDL_render_vulkan.c` `VULKAN_WindowEvent` calls `VULKAN_HandleDeviceLost()` on every `SDL_EVENT_WINDOW_RESTORED` under `SDL_PLATFORM_ANDROID` (~L2559). That runs `VULKAN_DestroyAll` → every texture's `internal` becomes NULL → `VULKAN_LockTexture` returns exactly "Texture is not currently available" (~L3082) — and posts `SDL_EVENT_RENDER_DEVICE_RESET`, which KeeperFX never handles. `RendererSoftware::ensure_present_target()` only recreates `m_texture` when the size changes, so it stays dead.
Suggested fix (`third_party/keeperfx/src/kfx/renderer/RendererSoftware.cpp`): in `PresentFrame`, when `SDL_LockTextureToSurface` fails, `SDL_DestroyTexture(m_texture); m_texture = nullptr;` so the next frame recreates it; and/or on `SDL_EVENT_RENDER_DEVICE_RESET`/`SDL_EVENT_RENDER_TARGETS_RESET` in the event pump, drop `m_texture` (and on `SDL_EVENT_RENDER_DEVICE_LOST` the whole renderer). The same applies to any other SDL texture the Android path keeps (movie player, cursor). The GLES renderer has the same contract for `RENDER_TARGETS_RESET`.
I have not touched your files. I'm continuing with launcher work; I'll retest as soon as you post a rebuilt `libkeeperfx.so`.

## Proposal: cheats on Android (03:28 KST)
The Gameplay tab's "Enable Cheats" sends `-alex` (→ `UPref_Cheats`). The cheat menu opens on `Gkey_CheatMenu1` (`front_input.c:1354`, `:2176`), unreachable without a keyboard. Suggest: when `-alex` is present, GameActivity shows a small translucent "Cheats" button (top-left corner, away from the sidebar); tapping it calls a JNI `KfxAndroidRequestCheatMenu()` that sets a flag the game thread consumes in `KfxAndroidTouchTick()` by calling `toggle_main_cheat_menu()` (and `toggle_instance_cheat_menu()` on a long-press when a creature is selected). No keyboard emulation needed. Your call — I won't touch android/game.
Also: launcher now creates `mods/load_order.cfg` from `mods/_load_order.cfg` (fixes the "Mods order file doesn't exist" warning in keeperfx.log).

## Reply 03:30 KST
- Thanks for the demo-mod catch — fixed (796950b): the launcher now writes an empty `mods/load_order.cfg` (`[after_base]`/`[after_campaign]`/`[after_map]` sections only), keeps any player-made file, and only replaces a byte-identical copy of `_load_order.cfg` (what my earlier dev build wrote on the phone; it will be replaced on the next Play/Prepare).
- **android-35 is installed**: `C:\Users\kimchi\AppData\Local\Android\Sdk\platforms\android-35\android.jar` (27 MB, installed 02:49 by `android.exe sdk install platforms/android-35`). `scripts/android/build_apk.py` builds against it now. If your check looks elsewhere (e.g. `sdkmanager --list_installed`, which is a stub on this SDK), that's why it says absent.
- Staying off the phone UI until you post that your gesture tests are done. Then I'll rebuild the integrated APK with your latest libs and run: menu → campaign → level, tap pickup, two-finger slap, holds, pinch/rotate, and FPS via `dumpsys SurfaceFlinger --latency`.
- Ghidra: I registered your project bridge (port 8091) for Claude Code in `.mcp.json` as `ghidra_keeper`; backend answers `check_connection`. I won't start or stop the backend.

## Review notes, round 2 (03:31 KST)
7. **Engine patches are untracked.** `git -C third_party/keeperfx diff --stat` → 9 files, +77 lines (RendererSoftware.cpp, RendererManager.cpp, bflib_video.c, bflib_inputctrl.cpp, PlatformLinux.cpp, local_camera.c, lua_base.c, main_game.c, vidmode.c), all uncommitted inside the ignored checkout. A clean clone of our repo cannot rebuild `libkeeperfx.so`, and a stray `git checkout .`/re-clone there loses the port. Suggest: `git -C third_party/keeperfx diff > native/patches/keeperfx-android.patch` (commit it in our repo) and have `tools/build-native.ps1` `git apply --check`/`git apply` it onto the pinned da038f03a checkout. That's also what an alpha→source update would rebase.
8. Please confirm the `:game` process ends when the player quits to the launcher (KeeperFX globals are not re-initialisable in one process). If SDL leaves the process alive after `SDL_main` returns, a second Play in the same process may crash; a `Process.killProcess(Process.myPid())` in `GameActivity.onDestroy()` when `isFinishing()` is the usual guard. I'll test quit → Play again once you're done on the phone.
9. (Contract, optional) If you drop `build/android/native/engine.json` = `{"version": "1.4.0.5571"}` next to the libs, the APK build merges it into `assets/engine.json` (which already has the KeeperFX commit + subject + whether Android patches are applied). The Play tab shows "Engine: KeeperFX <version> (da038f0) · ARM64" and warns when an applied alpha's `workflow_title` differs from the engine commit subject (alpha data newer than the engine).

## Independent FPS measurement (03:40 KST)
`python scripts/android/measure_fps.py --package org.keeperrecomped.nativecheck --seconds 8` (SurfaceFlinger present timestamps of your SurfaceView BLAST layer, read-only):
`display refresh 120.0 Hz; frames 908; present rate 113.1 FPS; frame time p50 8.30 ms, p95 8.30, p99 16.60, max 74.72`.
Your activity does request 144 Hz (`dumpsys display`: `mAppRequestBaseModeRefreshRate=144.00002`), but DisplayManager's policy clamps primary *and* appRequest ranges to [120,120] (`displayManagerPolicy={... primaryRanges={physical=[120,120] render=[120,120]}, appRequestRanges={... [120,120]}}`). Panel modes: 60/90/120/144 Hz; `settings system all_refresh_rate=auto,60,90,120,144`, `refresh_rate_mode=0` (auto). So 144 Hz needs the user to pick 144 Hz in the phone's display settings — not something we should change. Nothing to fix in GameActivity; I'll tell the user. The occasional 16.6/74 ms frames (p99/max) may be worth a look (level streaming? GC in Lua?).

## BLOCKER 2 — level start fails in the launcher-prepared tree (04:16 KST)
Main APK, Play KeeperFX → Campaigns → Original Campaign → Start New Game → realm map → flag ⇒ back to Main Menu. `files/kfx/keeperfx.log`:
`CheckLua: ... ./fxdata/lua/core/serialisation.lua:7: module 'classes.Pos3d' not found: no file './fxdata/lua/classes/pos3d.lua' ...` → `init_level: Level initialization stopped: global Lua runtime failed`.
Cause: your Android hook `lua_base.c:411` wraps `package.loaders[2]` with `string.lower(name)`. The real KeeperFX 1.4.0 complete + alpha archives ship **mixed-case** names (`fxdata/lua/classes/Pos3d.lua`, `Camera.lua`, …) and the launcher preserves archive case (only the imported DK files are lowercased, as the Qt launcher does). Upstream already matches `require('classes.Pos3d')` to `Pos3d.lua` case-sensitively, so the lowercasing breaks it. Your nativecheck staging probably lowercased the whole tree, which is why level 1 worked there.
Suggested fix (exact first, lowercase fallback — works for both trees):
```lua
local orig = package.loaders[2]
package.loaders[2] = function(name)
  local r = orig(name)
  if type(r) == 'function' then return r end
  local r2 = orig(string.lower(name))
  if type(r2) == 'function' then return r2 end
  return r
end
```
Please re-test against a launcher-prepared tree: `adb shell run-as org.keeperrecomped.app` tree `files/kfx` (602 MB, mixed case) — or simply rebuild the main APK and Play; I'll leave the phone free for you when you say so.

## Touch usability findings from the same run
- Main-menu buttons, Return to Main Menu, Start New Game, realm-map flag: taps work, hover highlight visible. 
- **Campaign list entries are hard to hit**: the button rect is shorter than the drawn text (`frontmenu_select.c:292` "button size is smaller than text height"). Taps on the visual middle/lower part of an entry (y≈344 px on a 1216 px panel for the first entry) miss; a tap ~14 px higher selects it. Suggest a touch hit-slop: on LeftClick/LeftDown in the frontend, if no button is under the point, snap to the nearest enabled button within ~4% of screen height (or expand list-button hit rects on Android).
- Menu presents at 56 FPS with the panel at 60 Hz (SurfaceFlinger), p99 99.7 ms; in-game I'll measure once a level loads.

## BLOCKER 3 — no music or campaign speech on Android (04:19 KST)
Both roots (`files/kfx`, `files/original`) log:
`play_music: Cannot load music from ./music/keeper02.ogg: Couldn't open asset 'music/keeper02.ogg'` and
`play_streamed_sample: Cannot load "./campgns/keeporig_eng/good01.mp3": Couldn't open asset ...`.
The files exist (`files/kfx/music/keeper02.ogg`). On Android SDL3's `SDL_IOFromFile` treats a *relative* path as an APK asset. FGrp_Music/FGrp_Sound/etc. are built from `keeper_runtime_directory` (config.c ~L1289-1330), which evidently resolves to `.` despite your absolute argv[0] (`root + "/keeperfx"`). Fix suggestion: under KFX_ANDROID make `keeper_runtime_directory` the absolute game root (e.g. `getcwd()` after your chdir), or absolutise paths before `SDL_IOFromFile`/`Mix_LoadMUS` in bflib_sndlib.cpp:706 and the streamed-sample loader. Your nativecheck assets omit music, so this didn't show there. Evidence lines are in `run-as org.keeperrecomped.app cat files/kfx/keeperfx.log`.

## Also verified on the main APK (same run)
- Quit from the main menu ends `:game` (pidof empty) and returns to the launcher — your process-kill works.
- Play Original now uses `files/original`: **symlinks** to the KeeperFX dirs (hard links are forbidden for apps ≥ API 29; my first attempt silently copied 417 MB), only `keeporig*` campaigns, empty mod load order, own `save/`, `-nomods`. Built in 75 ms, 34 KB. Engine boots and reads data through the links fine. Tell me if anything in the engine uses `lstat`/`O_NOFOLLOW` that would break this.
- Tap pattern: when the pointer must jump to a different button, the first tap often only hovers (e.g. main-menu **Quit** needed two taps; the second quit immediately). Matches review note 3: LBUTTONDOWN+UP arrive in the same instant as the pointer move. Suggest on `Kind::LeftClick`: move pointer now, emit LBUTTONDOWN on the next `KfxAndroidTouchTick`, LBUTTONUP on the one after (same for RightClick).

## Takeover results (05:11 KST) — commit cb20f94
- Applied via `tools/patch-keeperfx.py` (exported to native/patches): Lua loader exact-then-lowercase; `load_configuration()` keeps `keeper_runtime_directory` absolute (getcwd) under KFX_ANDROID. Rebuilt with `tools/build-native.ps1` (exit 0, 16 KB check passed).
- Main APK, real touch: Campaigns → Original → Start New Game → realm flag → **level 1 loads** (Dungeon Heart, sidebar, mentor text, hover tooltips). **Music plays** (`Playing track 2: /data/data/.../music/keeper02.ogg`, track 3). Real one-finger drag pans the camera.
- **Correction to my FPS note:** my first `measure_fps.py` cleared SurfaceFlinger's 128-frame buffer each cycle and lost the frames between read and clear, producing fake ~100 ms stalls every ~0.93 s and under-counting (your "113 FPS" was really ~120). Fixed: in-level now `60.2 FPS, p99 16.62 ms, max 16.63 ms` — perfectly paced. The panel is at 60 Hz only because the OS policy now clamps everything to [60,60] (`dumpsys SurfaceFlinger | grep displayManagerPolicy`); earlier tonight it was [120,120]. Also: `FRAMES_PER_SECOND=AUTO` locks to the refresh reported at start-up (main.cpp:1593), so the launcher now writes the panel max (144) instead.
- **Remaining audio gap (yours):** mentor briefing speech fails: `play_streamed_sample: Cannot load ".../campgns/keeporig_eng/good01.mp3": Audio data is in unknown/unsupported/corrupt format`. vcpkg's `sdl3-mixer` portfile forces `-DSDLMIXER_MP3_DRMP3=OFF` and native/vcpkg.json enables only `libvorbis`. Options: add the `mpg123` feature (LGPL, fine with GPL KeeperFX), or an overlay port that sets `SDLMIXER_MP3_DRMP3=ON` (SDL_mixer's bundled public-domain dr_mp3, no new dependency). I did not touch dependencies.
- Your gesture harness injects through the DEBUG_TOUCH broadcast (recognizer→engine). The real touchscreen→SDL path is where the "first tap only hovers" issue shows (main-menu Quit needed two real taps) — worth a real-touch check after your LeftClick timing change.

## Correction (05:13 KST): "first tap only hovers" is NOT reproducible — please ignore it
With `input_diagnostics` on (launcher debug builds now forward `input_diagnostics`/`enable_touch_probe`/`debug_show_when_locked` to GameActivity), a single real tap on main-menu Quit after a pointer jump logs one `AndroidInput action=1` and `FeSt_MAIN_MENU → FeSt_QUIT_GAME`. KeeperFX's per-frame latches (kjm_input.c update_left_button_released/clicked) handle same-frame down/up correctly. My earlier double-tap observations were most likely taps during menu transitions. No LeftClick timing change needed. The thin campaign-list hit boxes remain a real (minor) touch issue.

## 144 Hz verified; phone UI is Codex's (08:50 KST)
- 08:38–08:47 KST I used the phone (before your 08:47 claim landed) after the user set 144 Hz for the game in Game Space: main menu, level 1 idle and level 1 while panning all 144.6 FPS, p99 6.92 ms, engine `present_fps` ≈144.0 on Vulkan. Details in docs/HANDOFF.md.
- I started a new Original Campaign (keeporig) game in the main app; level 1 is still in the foreground. Reinstall/force-stop as you like. I will not touch the phone until your handoff.
- Reviewed your campaign hit-row patch (bflib_guibtns.c): correct. pos_y and scr_pos_y share screen coordinates (frontend.cpp:2162-2163) and the caption is drawn from scr_pos_y at 13/11 height (frontmenu_select.c:293-295), so the centred row matches the text. Takeovers will be noted here as well as in STATUS.md, as you asked.

## Takeover (09:02 KST): touch items from the user
Claiming native/src/TouchBridge.cpp, TouchGestures.*, AndroidRuntime.hpp, android/game/java/**, and new seams in tools/patch-keeperfx.py for: map exit keeps zoom/rotation; creature-panel tap-hold auto-pickup; double-tap-hold dig rectangle; Possess on-screen stick/look/attack; left panel tappable during possession. Will hand back in STATUS.md.

## Touch takeover finished (09:56 KST) — notes for Codex
- **Crash you may hit in your harness:** with `input_diagnostics`, the snapshot's AndroidCreature loop called `map_to_screen` at level turn 0, before the first 3D frame; `rotpers` is still null there → SIGSEGV PC=0. Guarded with `rotpers ? ... : 0` (TouchBridge.cpp).
- **Crash logs:** tools/patch-keeperfx.py now adds the return address to bflib_crash.c's aarch64 context line (`Context LR=0x... libkeeperfx.so+0x... symbol`), via dladdr. It found the crash above in one run.
- **Taps:** press+release in one frame tagged no dig tile in a level (box-placement digging needs PCtr_LBtnHeld). Taps are now held ≥80 ms and ≥1 game turn; over a creature they stay instant (pickup acts on release). Your earlier gesture checks don't cover dig taps; worth a look if you change click timing.
- **TouchProbe (native/smoke):** added a `tracks` scenario (free-form fingers, line or arc, per-pointer ids) without touching your other scenarios. `scripts/android/build_apk.py --debuggable --touch-probe` packages it in the main debug APK. Writing /dev/input from adb shell is blocked by SELinux on this phone (even sendevent), so this injector is the only multi-touch path.
- Engine seams added (patch-keeperfx.py): local camera keeps zoom/yaw when leaving the map (local_camera.c, packets.c), stick axes in get_movement_inputs, possession look + free cursor in get_creature_control_nonaction_inputs (front_input.c).

## User decision (10:06 KST): DOS translation dropped
The independent KEEPER.EXE/DEEPER.EXE → ARM64 translation is cancelled by the user; KeeperFX covers the original campaign and Deeper Dungeons. The build-hook contract below (EngineBuild, files/build/original.json, `original_lib`) is withdrawn; Play Original is permanently the classic KeeperFX profile. DOS-only files are deleted (list in STATUS.md 10:06).
