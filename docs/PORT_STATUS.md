# Android port status

This document records verified work, not a claim that every requested milestone is complete.

## Sources and architecture
- KeeperFX source: `dkfans/keeperfx`, revision `da038f03ab5f472dc570045f49cbfa76e7101ec4`, matching the supplied Windows Alpha 1.4.0.5571.
- Original game executables (`KEEPER.EXE`, `DEEPER.EXE`) are not used. The user dropped their independent ARM64 translation on 2026-10-29: KeeperFX already plays the original campaign and the Deeper Dungeons levels from the player's data files.
- Android target: ARM64 only, minimum Android API 35; connected device reports API 36 and 144 Hz support.
- Native backend initially combines existing CPU indexed rasterization with SDL3's actual Vulkan presentation. A GPU world renderer is separate work; do not confuse these.
- Original campaign/classic rules can run through the rebuilt KeeperFX engine. That mode must not be represented as an independently compiled original executable.

## Verified milestones
- Local Git repository initialized; game assets and generated build outputs excluded.
- Ghidra 12.1.3 copied into the project's analysis pack; Python MCP bridge installed in its own environment.
- Original-executable analysis (Ghidra LE import of KEEPER.EXE/DEEPER.EXE, pseudocode exports, SR static-translation probe) was dropped on 2026-10-29 at the user's request; its scripts, exports and recompiler checkouts were deleted. The scripts remain in Git history before that commit.
- All six supplied configuration tests pass using the installed Ghidra Python environment.
- Touch recognizer cross-compiled and executed on Android ARM64: 11 scenarios passed, including pinch/rotation exclusion, multi-touch lift ordering, holds, cancellation, and menu/world dragging.
- Native engine and all runtime dependencies cross-compiled successfully for Android ARM64. `SDL_main` is exported and runtime dependencies have been audited.
- Upstream save codec suite executed on the phone: 14,571 checks, zero failures, including full game-state round trip.
- Lua/BitOp/registration/serialization regression suite executed on the phone: seven checks passed with Android FORTIFY enabled. A weak-table GC string scan was patched after an actual level-start crash.
- Ghidra MCP live stdio verification passed: 260 tools and healthy project-specific backend on loopback port8091.
- Original campaign level1 visibly runs in the integrated launcher APK at1280x579 with Vulkan presentation. A ten-second SurfaceFlinger sample measured144.6FPS, p50/p95/p99 frame time6.91ms, maximum13.83ms; native telemetry independently reports about144FPS. This measures the first level, not every map.
- Device display policy varied between60/120/144Hz during testing. The latest run reached144Hz without Codex changing global refresh settings. The app requests the supported maximum, capped at165Hz.
- Eight integrated gameplay touch checks passed: creature pickup, two-finger right-click drop, two-finger slap (Imp health75→73), pinch without rotation, rotation without zoom, pan, left hold, right hold. Snapshot aiming against moving creatures required retries; the report records them.
- OpenAL initialized the actual OpenSL backend and loaded1068 sound/126 speech samples. Overnight testing used only the test app's sound gains set to0; global phone volume was preserved.
- Engine, SDL3 and libc++ ELF segments pass16KB page alignment checks. `build/android/native/engine.json` records native version, source revision, ABI and library hashes.
- Runtime fixes include Lua's case-sensitive module search after lowercase import, clean failure on missing global Lua init, consistent logical draw dimensions, and recreation of SDL presentation resources after device/target reset.
- Launcher-prepared mixed-case Lua files also load correctly (exact path first, lowercase fallback). Absolute runtime paths fix Android SDL treating music as APK assets; the SDL3_mixer overlay enables campaign MP3 speech with dr_mp3. Integrated logs confirm both Ogg music and good01.mp3 playback.
- Campaign touch rows now align with their drawn caption centres. A real injected Android tap at the previously missed visual centre selected the correct campaign. Android Back was visually verified opening the in-level Options menu after closing the current UI state.

## Build commands
1. `powershell -ExecutionPolicy Bypass -File tools/build-dependencies.ps1`
2. `powershell -ExecutionPolicy Bypass -File tools/build-native.ps1`
3. Launcher packaging is owned by Claude under `scripts/android/`; consult that directory for the current APK build command.
4. `powershell -ExecutionPolicy Bypass -File tools/test-touch.ps1`

## Current limitations
- The supplied CE workbench package omits nine required runtime/analysis modules, also absent from the supplied ZIP and manifest. It cannot start as supplied. Do not register it as healthy or substitute stubs for missing functions.
- Desktop alpha packages include Windows binaries; Android may import compatible game data, but engine changes require a matching Android build. Updates must not overwrite ARM64 libraries with Windows files or silently claim native version parity.
- FPS requires actual gameplay measurement. Touch tests and menu presentation are not gameplay performance proof.
