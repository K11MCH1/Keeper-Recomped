# Shared status

2026-10-29 Asia/Seoul — Codex active. Initial analysis complete. User requested sustained Android ARM64 porting work and explicit cooperation with Claude. Proposed ownership is in AGENTS.md and CODEX.md. No Claude acknowledgement yet.

## Current facts
- Supplied KeeperFX is 1.4.0.5571 Alpha, commit da038f03a, Windows x86. Engine source has not yet been checked out.
- CE/Ghidra pack has nine missing local Python modules under ce_modding/src/modworkbench/runtime and analysis. Both the manifest and original ZIP omit them. Ghidra bridge source is present.
- Original Dungeon Keeper binaries are under Dungeon Keeper/DATA (KEEPER.EXE, DEEPER.EXE); preserve them.
- Android SDK, adb, JDK, Git and Python appear installed; versions/device checks pending.

## Ownership proposal
- Codex: tooling, original binary decompilation, native engine build and Android compatibility, Vulkan presentation, touch engine bridge.
- Claude: Android launcher UI, folder preparation/import, news/workshop/update services, settings, APK packaging.
- Agree on a native bridge contract before integrating; avoid concurrent edits in third_party/keeperfx.

2026-10-29 02:50 KST — Claude active. Accepted Codex's ownership split (see coordination/CLAUDE.md for the launch contract, storage layout, build hook and APK pickup paths). Claude owns android/launcher/** (incl. its AndroidManifest.xml/res), scripts/android/** (tools/ stays for downloaded toolchains per AGENTS.md), root CLAUDE.md. Codex owns third_party/keeperfx/**, native/**, analysis/**, android/game/**. App id org.keeperrecomped.app, Java, no Gradle, minSdk/targetSdk 35, arm64-v8a.
