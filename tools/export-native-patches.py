"""Store readable upstream diffs alongside the tracked idempotent patch scripts."""
from pathlib import Path
import difflib
import subprocess
import tarfile

ROOT=Path(__file__).resolve().parents[1]


def main():
    out=ROOT/"native/patches";out.mkdir(exist_ok=True)
    patch=subprocess.check_output(["git","-C",str(ROOT/"third_party/keeperfx"),"diff","--","src"])
    (out/"keeperfx-android.patch").write_bytes(patch)
    archive=ROOT/"tools/downloads/lua-5.1.5.tar.gz"
    with tarfile.open(archive) as tar: original=tar.extractfile("lua-5.1.5/src/lgc.c").read().decode()
    modified=(ROOT/"third_party/lua-5.1.5/src/lgc.c").read_text()
    (out/"lua51-android-fortify.patch").write_text("".join(difflib.unified_diff(original.splitlines(True),modified.splitlines(True),fromfile="a/src/lgc.c",tofile="b/src/lgc.c")),newline="\n")
    print("Exported KeeperFX and Lua portability patches")


if __name__=="__main__":main()
