"""Fetch the exact source revisions used by the native Android build."""
from pathlib import Path
import hashlib
import json
import subprocess
import tarfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


def run(*args):
    subprocess.run(args, check=True)


def main():
    lock = json.loads((ROOT / "third_party.lock.json").read_text())
    base = ROOT / "third_party"
    base.mkdir(exist_ok=True)
    for name, entry in lock["repositories"].items():
        destination = base / name
        if not destination.exists():
            run("git", "init", str(destination))
            run("git", "-C", str(destination), "remote", "add", "origin", entry["url"])
            run("git", "-C", str(destination), "fetch", "--depth", "1", "origin", entry["commit"])
            run("git", "-C", str(destination), "checkout", "--detach", "FETCH_HEAD")
        else:
            actual = subprocess.check_output(["git","-C",str(destination),"rev-parse","HEAD"], text=True).strip()
            if actual != entry["commit"]:
                raise RuntimeError(f"{name}: expected {entry['commit']}; found {actual}. Preserve local changes before changing revisions.")
    downloads = ROOT / "tools/downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    for name, entry in lock["archives"].items():
        archive = downloads / f"{name}.tar.gz"
        if not archive.exists():
            urllib.request.urlretrieve(entry["url"], archive)
        if hashlib.sha256(archive.read_bytes()).hexdigest() != entry["sha256"]:
            raise RuntimeError(f"Hash mismatch: {archive}")
        if not (base / name).exists():
            with tarfile.open(archive) as package:
                package.extractall(base, filter="data")
    run("py", "-3.13", str(ROOT / "tools/patch-keeperfx.py"))
    run("py", "-3.13", str(ROOT / "tools/patch-lua.py"))
    patch = ROOT / "tools/ghidra/loader-final-page.patch"
    destination = base / "ghidra-lx-loader"
    result = subprocess.run(["git","-C",str(destination),"apply","--reverse","--check",str(patch)], capture_output=True)
    if result.returncode:
        run("git","-C",str(destination),"apply",str(patch))
    print("Upstream revisions verified; Android and LE loader patches applied")


if __name__ == "__main__":
    main()
