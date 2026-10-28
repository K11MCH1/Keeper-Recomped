# Android port status

This document records verified work, not a claim that every requested milestone is complete.

## Sources and architecture
- KeeperFX source: `dkfans/keeperfx`, revision `da038f03ab5f472dc570045f49cbfa76e7101ec4`, matching the supplied Windows Alpha 1.4.0.5571.
- Original game: supplied `Dungeon Keeper/DATA/KEEPER.EXE` and `DEEPER.EXE`, DOS4GW LE, x86 32-bit.
- Android target: ARM64 only, minimum Android API 35; connected device reports API 36 and 144 Hz support.
- Native backend initially combines existing CPU indexed rasterization with SDL3's actual Vulkan presentation. A GPU world renderer is separate work; do not confuse these.
- Original campaign/classic rules can run through the rebuilt KeeperFX engine. That mode must not be represented as an independently compiled original executable.

## Verified milestones
- Local Git repository initialized; game assets and generated build outputs excluded.
- Ghidra 12.1.3 copied into the project's analysis pack; Python MCP bridge installed in its own environment.
- Ghidra LE loader built against 12.1.3 and fixed to identify a short final physical page independently of the final object. This fixes the supplied original game's import failure.
- Original KEEPER.EXE imported with the Watcom 32-bit language/compiler specification. All 2,279 recognized functions decompiled with zero decompiler failures. Output is under ignored `analysis/original/KEEPER.EXE`; it is pseudocode requiring ABI/type/global reconstruction before standalone recompilation.
- All six supplied configuration tests pass using the installed Ghidra Python environment.
- Touch recognizer cross-compiled and executed on Android ARM64: 11 scenarios passed, including pinch/rotation exclusion, multi-touch lift ordering, holds, cancellation, and menu/world dragging.

## Build commands
1. `powershell -ExecutionPolicy Bypass -File tools/build-dependencies.ps1`
2. `powershell -ExecutionPolicy Bypass -File tools/build-native.ps1`
3. Launcher packaging is owned by Claude under `scripts/android/`; consult that directory for the current APK build command.
4. `powershell -ExecutionPolicy Bypass -File tools/test-touch.ps1`
5. `powershell -ExecutionPolicy Bypass -File tools/decompile-original.ps1 [-Binary DEEPER.EXE] [-Resume]`

## Current limitations
- The supplied CE workbench package omits nine required runtime/analysis modules, also absent from the supplied ZIP and manifest. It cannot start as supplied. Do not register it as healthy or substitute stubs for missing functions.
- Desktop alpha packages include Windows binaries; Android may import compatible game data, but engine changes require a matching Android build. Updates must not overwrite ARM64 libraries with Windows files or silently claim native version parity.
- FPS requires actual gameplay measurement. Touch tests and menu presentation are not gameplay performance proof.
- Original-game decompilation exports are analysis evidence, not yet an independently executable ARM64 original-engine port.
