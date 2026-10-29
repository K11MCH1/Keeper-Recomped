"""Probe genuine DOS/LE static translation; never substitutes an emulator.

Outputs remain private generated game code under build/. A successful SR pass
would still need llasm/LLVM compilation and Dungeon Keeper-specific DOS runtime
replacement before it could become a runnable independent original engine.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[2]
REVISION = "ac690ddf3010bc3d2c875cb1b5e8da4f53a8d5b3"
ORIGIN = "https://github.com/M-HT/SR.git"


def run(*args, **kwargs):
    return subprocess.run([str(a) for a in args], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", choices=["KEEPER.EXE", "DEEPER.EXE"], default="KEEPER.EXE")
    args = parser.parse_args()
    upstream = ROOT / "third_party/SR"
    if not upstream.exists():
        run("git", "clone", "--filter=blob:none", "--no-checkout", ORIGIN, upstream)
        run("git", "-C", upstream, "sparse-checkout", "set", "SR", "llasm")
        run("git", "-C", upstream, "fetch", "--depth", "1", "origin", REVISION)
        run("git", "-C", upstream, "checkout", "--detach", "FETCH_HEAD")
    actual = subprocess.check_output(["git", "-C", str(upstream), "rev-parse", "HEAD"], text=True).strip()
    if actual != REVISION:
        raise RuntimeError(f"SR revision differs: expected {REVISION}; found {actual}")
    work = ROOT / "build/static-recomp"
    source = work / "probe-source"
    shutil.copytree(upstream / "SR", source, dirs_exist_ok=True,
                    ignore=shutil.ignore_patterns("*.obj", "*.exe", ".sconsign*"))
    edits = {
        "SR_defs.h": [("#define OUTPUT_TYPE  OUT_X86", "#define OUTPUT_TYPE  OUT_LLASM")],
        "SR_loader.c": [
            ("Entry == Header->NumObjectsInModule - 1 && RemainingPages == 1",
             "CurPage + 1 == Header->MouduleNumOfPages && Header->BytesOnLastPage")],
        "SR_full_llasm_instr.c": [
            ("    if (cOutput[0] == 0)\n",
             "    if (cOutput[0] == 0 && ud_obj.mnemonic == UD_Itest && flags_to_write == 0\n"
             "        && ud_obj.operand[0].type == UD_OP_REG && ud_obj.operand[1].type == UD_OP_REG)\n"
             '        OUTPUT_STRING("; register test with no live flags\\n");\n'
             "    if (cOutput[0] == 0)\n")],
    }
    for name, replacements in edits.items():
        path = source / name
        text = path.read_text()
        for before, after in replacements:
            if text.count(before) != 1:
                raise RuntimeError(f"Unexpected SR source anchor: {name}")
            text = text.replace(before, after)
        path.write_text(text, newline="\n")
    sdk = Path(os.environ.get("ANDROID_HOME", str(Path.home()/"AppData/Local/Android/Sdk")))
    cmake = sdk / "cmake/3.22.1/bin/cmake.exe"
    host = work / "probe-host"
    with (work / "probe-build.log").open("w") as log:
        run(cmake, "-S", Path(__file__).parent, "-B", host, "-G", "Visual Studio 17 2022",
            "-A", "x64", f"-DSR_SOURCE={source.as_posix()}", stdout=log, stderr=subprocess.STDOUT)
        run(cmake, "--build", host, "--config", "Release", "--parallel", "12",
            stdout=log, stderr=subprocess.STDOUT)
    binary = ROOT / "Dungeon Keeper/DATA" / args.binary
    image = binary.read_bytes()
    le = struct.unpack_from("<I", image, 0x3c)[0]
    if image[le:le+4] != b"LE\0\0":
        raise RuntimeError("Expected the supplied original LE binary")
    table, count = struct.unpack_from("<II", image, le + 0x40)
    objects = []
    for i in range(count):
        size, base, flags, first, pages, _ = struct.unpack_from("<6I", image, le + table + i * 24)
        objects.append(dict(number=i+1, base=hex(base), size=size, flags=hex(flags),
                            executable=bool(flags & 4), first_page=first, page_count=pages))
    output = work / args.binary
    output.mkdir(exist_ok=True)
    result = subprocess.run([str(host / "Release/SR.exe"), str(binary), "game.llasm"],
                            cwd=output, capture_output=True, text=True)
    (output / "translation.log").write_text(result.stdout + result.stderr)
    report = dict(binary=args.binary, sha256=hashlib.sha256(image).hexdigest(),
                  translator=ORIGIN, revision=REVISION, target="LLasm to LLVM to ARM64",
                  objects=objects, translator_exit=result.returncode,
                  translation_finished=result.returncode == 0, runnable_original_engine=False,
                  diagnostic_tail=(result.stdout + result.stderr).splitlines()[-8:],
                  remaining=["Resolve instruction/region flag diagnostics using verified control flow",
                             "Identify indirect call targets, 16-bit code, and code/data boundaries",
                             "Replace DOS/DPMI, VESA, input, timers, file and audio services",
                             "Compile LLasm/LLVM and validate native gameplay against the original"])
    report_path = output / "report.json"
    report_path.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"report": str(report_path), "translation_finished": report["translation_finished"],
                      "runnable_original_engine": False, "diagnostics": report["diagnostic_tail"]}, indent=2))
    return 0 if result.returncode == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
