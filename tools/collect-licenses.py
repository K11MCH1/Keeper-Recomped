"""Collect source and binary dependency notices into the APK's credits assets."""
from pathlib import Path
import json
import shutil

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / "android/game/assets/licenses/native"


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    lock=json.loads((ROOT/"third_party.lock.json").read_text())
    recorded=[]
    for name,entry in lock["repositories"].items():
        source=ROOT/"third_party"/name
        for candidate in ("LICENSE","LICENSE.txt","COPYING","COPYING.txt","COPYRIGHT"):
            path=source/candidate
            if path.is_file():
                filename=f"{name}-{candidate}.txt"
                shutil.copyfile(path,OUT/filename)
                recorded.append({"name":name,"source":entry["url"],"commit":entry["commit"],"notice":filename})
                break
    for name in lock["archives"]:
        source=ROOT/"third_party"/name
        for candidate in ("COPYRIGHT","COPYING","LICENSE","README"):
            path=source/candidate
            if path.is_file():
                filename=f"{name}-{candidate}.txt";shutil.copyfile(path,OUT/filename)
                recorded.append({"name":name,"source":lock["archives"][name]["url"],"notice":filename})
                break
    installed=Path.home()/"kfx-build/installed/arm64-android-kfx/share"
    for path in sorted(installed.glob("*/copyright")):
        filename=f"dependency-{path.parent.name}.txt";shutil.copyfile(path,OUT/filename)
        recorded.append({"name":path.parent.name,"notice":filename,"build":"native/vcpkg.json and pinned vcpkg baseline"})
    (OUT/"index.json").write_text(json.dumps(recorded,indent=2)+"\n",encoding="utf-8")
    (OUT/"NOTICE.txt").write_text("Keeper Recomped Android native integration\n\nDungeon Keeper: Bullfrog Productions and its rights holders. Original proprietary game assets are supplied by the player and are not included in this APK.\nKeeperFX: KeeperFX contributors, GNU GPL version 2 or later.\nAndroid integration and gesture implementation: project contributors.\nSDL3 Java and native components: SDL contributors; see included SDL notice.\nThis build uses CPU world rasterization with Vulkan/GLES presentation. Classic mode uses KeeperFX's original campaign and classic rules.\n\nNative dependencies and exact source references are listed in index.json. Reproducible source checkout instructions and build manifests are in the project repository.\n",encoding="utf-8")
    print(f"Collected {len(recorded)} native dependency notices")


if __name__=="__main__":main()
