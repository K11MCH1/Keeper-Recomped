# Keeper Recomped

Dungeon Keeper for Android 15+ (ARM64): a launcher APK plus the KeeperFX engine compiled for ARM64, presented through Vulkan (OpenGL ES fallback), with touch controls. Your own Dungeon Keeper files are imported on the phone; KeeperFX data and alpha patches are downloaded from keeperfx.net. No game data is in the APK.

## Get started
- APK: `build/apk/keeper-recomped.apk` (debug, installed on the NX809J) or `build/apk/keeper-recomped-release.apk`.
- How to use the launcher, tabs, touch controls, Back button, frame-rate notes: [docs/LAUNCHER.md](docs/LAUNCHER.md).
- What works and what is verified on the phone: [docs/HANDOFF.md](docs/HANDOFF.md) (launcher, Claude) and [docs/PORT_STATUS.md](docs/PORT_STATUS.md) (engine, Codex).

## Build
```
python scripts/android/build_apk.py --output build/apk/keeper-recomped.apk --debuggable
```
Engine libraries: `tools/build-dependencies.ps1` then `tools/build-native.ps1` (vcpkg + NDK 27, ARM64, API 35). See [CLAUDE.md](CLAUDE.md) / [AGENTS.md](AGENTS.md) for the full toolchain and workflow.

## Layout
| Path | What |
|---|---|
| `android/launcher/` | Launcher app: WebView UI, prepare pipeline, keeperfx.net services, manifest (Claude) |
| `android/game/` | SDL3 Java glue + GameActivity (Codex) |
| `native/` | Android entry, touch gestures, build manifest, overlay ports, exported engine patches (Codex) |
| `tools/` | Engine build/patch scripts, Ghidra pack starter (Codex) |
| `scripts/android/` | APK builder, FPS probe, dev download relay (Claude) |
| `coordination/` | How Claude and Codex split and handed off the work |
| `third_party/`, `build/` | Ignored: source checkouts, build outputs |

KeeperFX is © the KeeperFX team and contributors (GPL-2.0). Dungeon Keeper is © Bullfrog Productions / Electronic Arts.
