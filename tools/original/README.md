# Independent original engine research

The original DOS4GW executables are LE32 images. The ARM64 KeeperFX library and
its classic campaign profile are separate from this experimental translation.

`py -3.13 tools/original/probe.py [--binary DEEPER.EXE]` builds a pinned copy of
[SR](https://github.com/M-HT/SR), selects its LLasm output, and probes the supplied
executable. It needs the installed MSVC 2022 build tools and SDK CMake. Source
and generated game code stay in ignored directories. No game code is committed.

The probe fixes SR's final-page assumption: the last initialized page can precede
a BSS object. It also allows an empty translation only for a register-only TEST
whose flags are dead; it never removes a live flags dependency or a memory read.
Unresolved instructions or incoming region flags stop translation and return a
nonzero exit status, with an explicit report under `build/static-recomp/`.

SR supports other DOS games, not Dungeon Keeper. Its output additionally needs
game-specific source information and native replacements for DOS services. This
probe does not provide those replacements, ARM64 machine code, or a runnable
original library. Do not write the launcher's `original.json` marker from it.

Ghidra analysis is available through `tools/decompile-original.ps1`; use
`-Reanalyze` to repair executable-section permissions and refresh pseudocode
after an analysis change, or `-Resume` to export only uncached functions.
