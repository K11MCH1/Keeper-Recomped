"""Install the isolated native-test APK and stage a lowercased test asset copy.

Writes only org.keeperrecomped.nativecheck's private files. Original desktop data
and the main launcher application are preserved. Proprietary data stays untracked.
"""
from pathlib import Path
import io
import subprocess
import tarfile

ROOT=Path(__file__).resolve().parents[1]
PACKAGE="org.keeperrecomped.nativecheck"


def run(*args):
    result=subprocess.run(args,capture_output=True,text=True,encoding="utf-8",errors="replace",check=True)
    print(result.stdout.strip())


def main():
    apk=ROOT/"build/native-smoke/keeper-native-test.apk"
    run("adb","install","-r",str(apk))
    run("adb","shell","run-as",PACKAGE,"mkdir","-p","files/game")
    archive=ROOT/"build/native-smoke/game-assets.tar"
    cfg={"INSTALL_PATH":"./","LANGUAGE":"ENG","FRONTEND_RES":"DESKTOP DESKTOP DESKTOP","INGAME_RES":"DESKTOP","RENDERER":"SOFTWARE","VSYNC":"TRUE","FREEZE_GAME_ON_FOCUS_LOST":"FALSE","CAPTURE_CURSOR":"FALSE","RELATIVE_MOUSE_MODE":"FALSE","CURSOR_EDGE_CAMERA_PANNING":"FALSE","POINTER_SENSITIVITY":"0","DELTA_TIME":"TRUE","TURNS_PER_SECOND":"20","FRAMES_PER_SECOND":"AUTO 144","STARTUP":"","MUSIC_FROM_DISK":"OFF","AUTOMATIC_REPLAYS":"FALSE","API_ENABLED":"FALSE"}
    seen=set();files=0
    with tarfile.open(archive,"w",format=tarfile.USTAR_FORMAT) as tar:
        for directory in ["campgns","creatrs","data","fxdata","ldata","levels","mods","multiplayer","sound"]:
            for source in sorted((ROOT/"KeeperFX"/directory).rglob("*")):
                if not source.is_file():continue
                name=source.relative_to(ROOT/"KeeperFX").as_posix().lower()
                if name in seen:raise RuntimeError(f"Case-folded asset collision: {name}")
                seen.add(name);files+=1
                info=tar.gettarinfo(str(source),arcname=name);info.mode=0o644;info.mtime=int(info.mtime);info.pax_headers={}
                with source.open("rb") as content:tar.addfile(info,content)
        text=(ROOT/"KeeperFX/keeperfx.cfg").read_text(encoding="utf-8-sig")
        lines=[];written=set()
        for line in text.splitlines():
            key=line.split("=",1)[0].strip() if "=" in line else ""
            if key in cfg:lines.append(key+"="+cfg[key]);written.add(key)
            else:lines.append(line)
        lines += [key+"="+value for key,value in cfg.items() if key not in written]
        data=("\n".join(lines)+"\n").encode();info=tarfile.TarInfo("keeperfx.cfg");info.size=len(data);info.mode=0o644;tar.addfile(info,io.BytesIO(data))
    # A device-side pipe avoids Windows adb stdin seek/EOF behavior on large files.
    run("adb","push",str(archive),"/data/local/tmp/keeper-native-assets.tar")
    run("adb","shell",f"cat /data/local/tmp/keeper-native-assets.tar | run-as {PACKAGE} tar -xf - -C files/game")
    print(f"Staged {files} game data files ({archive.stat().st_size/2**20:.1f} MiB tar) to the isolated test app")


if __name__=="__main__":main()
