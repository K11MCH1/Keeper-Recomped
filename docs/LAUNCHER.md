# Keeper Recomped for Android — launcher guide

## What you need
- An Android 15+ phone with a 64-bit (arm64) CPU and ~1.5 GB free space.
- Your own Dungeon Keeper files (GOG "Dungeon Keeper Gold", EA/Origin "Dungeon Keeper", or the CD), **or** a KeeperFX folder from a PC (which already contains them).
- Internet for the first Prepare (downloads KeeperFX 1.4.0, ~375 MB, plus the latest alpha patch, ~24 MB).

## First run
1. Copy the game folder to the phone, e.g. `Download/Dungeon Keeper` (for the EA version it must contain `DATA`).
2. Open **Keeper Recomped** → **Play** tab → **Choose folder…** and pick:
   - EA/Origin: `Dungeon Keeper/DATA` (or `Dungeon Keeper`; the launcher searches a few levels down),
   - GOG: the `Dungeon Keeper Gold` folder,
   - a PC KeeperFX folder: the folder that has `keeperfx.exe` / `fxdata`.
3. Press **Prepare & Build**. It imports your files into the app, downloads and unpacks KeeperFX, adds the alpha patch (Alpha channel), and writes `keeperfx.cfg`. The progress bar and a notification show each step; it keeps running with the screen off. Cancel from the same button or the notification.
4. Press **Play KeeperFX** for KeeperFX (campaign list, mods, multiplayer) or **Play Original** for the original campaign.

> **Play Original today** runs the original campaign on the KeeperFX engine compiled for ARM64 — the "classic profile": only the original campaign is offered, no mods are loaded, and it keeps its own save games separate from Play KeeperFX. The separate recompilation of the original DOS executable is still in progress; the button's caption says which one you are getting.

> **Frame rate:** the game asks for your screen's highest refresh rate (144 Hz on the NX809J), but Android decides the actual rate. If your phone's display refresh setting is on "auto", apps are usually held to 60 or 120 Hz; choose 144 Hz in the phone's display settings to let the game run at 144 FPS. On RedMagic/Nubia phones, **Game Space** adds Keeper Recomped automatically and has its own per-game frame-rate setting; set it to the maximum there too. With Game Space set to 144 Hz for the game, the NX809J runs menus and levels at a steady 144 FPS.

> **Back button:** in the game, Android Back works like the Escape key — it closes open panels, opens the Options menu (save, load, quit), and steps back through the main menus.

## Tabs
| Tab | What it does |
|---|---|
| Play | Folder, Prepare & Build, progress, update banner, Play buttons |
| Workshop | Latest maps/campaigns/mods from keeperfx.net; **Install** copies them into your KeeperFX game, **Details** opens the page |
| News | Latest keeperfx.net news |
| Graphics | Rendering Backend (Vulkan / OpenGL ES), Max. FPS (display maximum = the screen's top refresh rate), VSync, Resolution (Auto = screen shape at up to 1280 px wide; or 100%/75%/50% of native), viewport, movies, interface sizes, FPS counter |
| Gameplay | Language, Play intro, Play splash screens, Pause game when minimized, Enable Cheats, censorship, flee/imprison defaults, zoom, game speed, replays, sound |
| Controls | Empty for now (control settings later); the gestures are listed below |
| Advanced | Multiplayer (matchmaking server/port), API, Launcher (release channel, update checks, auto update, save backups, website integration, keep downloads), touch dead zones, maintenance |
| Credits | Bullfrog/EA, the KeeperFX team, libraries and licences |

## Touch controls (in game)
Tap = left click (pick up a creature) · tap & hold = left hold · two-finger tap on a creature = slap · two-finger tap elsewhere = right click · two-finger tap & hold = right hold · one-finger drag = move camera · pinch = zoom · two-finger twist = rotate (a dead zone stops pinches from rotating; tune it under Advanced → Touch tuning).

- **Dig a rectangle:** double tap and hold on rock, then drag; lifting the finger tags the whole box (starting on a tagged tile untags instead). A single tap still tags one tile; it waits a quarter second to see whether a second tap follows.
- **Creature panel:** tap and hold a creature count (idle, working, fighting) to keep picking those creatures up, several per second.
- **Map:** zoom and rotation stay as they were when you leave the parchment map, whether you tap a place or close it.
- **Possess Creature:** on-screen controls appear while you possess a creature. Left of the screen is an analog stick (it starts wherever your thumb lands); dragging on the right side turns the view; the claw button attacks (it fires when you lift, like a mouse click); the ✕ button leaves the creature. The side panel stays tappable: tap a spell to select it, tap the arrow at the bottom for the next page. Opening a menu (Back) hides the controls until you return.

## Updates
With **Automatically check for KeeperFX updates** on, the Play tab shows a banner when a new alpha patch (Alpha channel) or stable release is out; **Update** installs it (saves are backed up first if enabled). Alpha patches update KeeperFX's data and configs; the game engine itself is the one built into this app, so engine changes arrive with app updates.

## Where things are
- App storage: `files/dk` (your imported files), `files/kfx` (the KeeperFX game, saves in `files/kfx/save`), `files/save-backup/`.
- Logs: `files/logs/launcher.log` (launcher) and `files/kfx/keeperfx.log` (engine). Debug builds: `adb shell run-as org.keeperrecomped.app cat files/logs/launcher.log`.

## Building the APK (developers)
```
python scripts/android/build_apk.py --output build/apk/keeper-recomped.apk [--debuggable]
```
Needs JDK 17, Android SDK platform 35 + build-tools 35, NDK 27.3, the LZMA SDK under `third_party/lzma-sdk/C`, and Codex's engine libraries in `build/android/native/arm64-v8a/`. No Gradle. The APK never contains Dungeon Keeper data.
