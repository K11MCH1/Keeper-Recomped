"""Exercise real in-game touch actions in the isolated nativecheck APK.

Requires a running level with enable_touch_probe/input_diagnostics extras. Reads
engine-projected creature positions, so no moving-creature coordinates are guessed.
"""
from pathlib import Path
import json
import re
import subprocess
import time

ROOT=Path(__file__).resolve().parents[1]
PKG="org.keeperrecomped.nativecheck"
SNAP=re.compile(r"AndroidInput snapshot hand=(-?\d+) thing=(-?\d+) health=(-?\d+) camera=(-?\d+),(-?\d+) zoom=(\d+) yaw=(\d+)")
CREATURE=re.compile(r"AndroidCreature id=(\d+) owner=(\d+) model=(\d+) screen=(-?\d+),(-?\d+) norm=([\d.-]+),([\d.-]+) health=(-?\d+)")


def adb(*args):
    return subprocess.check_output(["adb",*args],text=True,encoding="utf-8",errors="replace")


def log():return adb("shell","run-as",PKG,"cat","files/game/keeperfx.log")


def action(scenario,**values):
    args=["shell","am","broadcast","-a",PKG+".DEBUG_TOUCH","--es","scenario",scenario]
    for key,value in values.items():args.extend(["--ef",key,str(value)])
    adb(*args)


def snapshot():
    before=len(log());action("snapshot")
    for _ in range(15):
        time.sleep(.1);text=log()[before:];match=SNAP.search(text)
        if match:
            keys=["hand","thing","health","camera_x","camera_y","zoom","yaw"]
            data=dict(zip(keys,map(int,match.groups())))
            data["creatures"]={int(m[0]):{"id":int(m[0]),"owner":int(m[1]),"model":int(m[2]),"x":float(m[5]),"y":float(m[6]),"health":int(m[7])} for m in CREATURE.findall(text)}
            return data
    raise RuntimeError("No live engine snapshot; check that nativecheck is running and the touch probe is enabled")


def main():
    report={"package":PKG,"checks":[]}
    def check(name,passed,**evidence):
        report["checks"].append({"name":name,"passed":bool(passed),**evidence})
        print(name,"PASS" if passed else "FAIL",flush=True)
    # Close the first-level information popup, if it is still visible.
    action("tap",x=.271,y=.96);time.sleep(.6)
    baseline=snapshot()
    candidates=[c for c in baseline["creatures"].values() if c["owner"]==0 and .2<c["x"]<.94 and .1<c["y"]<.85]
    if not candidates:raise RuntimeError("No owned creature visible for pickup test")
    creature=candidates[0];picked=baseline;pickupAttempts=0
    for _ in range(6):
        fresh=snapshot();choices=[c for c in fresh["creatures"].values() if c["owner"]==0 and .2<c["x"]<.94 and .1<c["y"]<.85]
        if not choices:continue
        creature=choices[0];pickupAttempts+=1
        action("tap",x=creature["x"],y=creature["y"]);time.sleep(.45);picked=snapshot()
        if picked["hand"]>baseline["hand"]:break
    check("single-tap creature pickup",picked["hand"]>baseline["hand"],before=baseline["hand"],after=picked["hand"],creature=creature["id"],attempts=pickupAttempts)
    action("twoTap",x=creature["x"],y=creature["y"]);time.sleep(.55);dropped=snapshot()
    check("two-finger right-click drop",picked["hand"]>baseline["hand"] and dropped["hand"]==baseline["hand"],before=picked["hand"],after=dropped["hand"])
    slap=[];success=False
    for _ in range(6):
        current=snapshot()
        choices=[c for c in current["creatures"].values() if c["owner"]==0 and .2<c["x"]<.94 and .1<c["y"]<.85]
        if not choices:continue
        target=choices[0]
        action("twoTap",x=target["x"],y=target["y"]);time.sleep(.45);after=snapshot()
        result=after["creatures"].get(target["id"])
        slap.append({"id":target["id"],"before_health":target["health"],"after_health":result["health"] if result else None})
        if result and result["health"]<target["health"]:success=True;break
    check("two-finger creature slap",success,attempts=slap)
    before=snapshot();action("pinch",x=.62,y=.43,amount=-.4);time.sleep(.8);after=snapshot()
    check("pinch zoom without rotation",after["zoom"]<before["zoom"] and after["yaw"]==before["yaw"],before_zoom=before["zoom"],after_zoom=after["zoom"],before_yaw=before["yaw"],after_yaw=after["yaw"])
    before=after;action("rotate",x=.62,y=.43,amount=30);time.sleep(.8);after=snapshot()
    check("rotation without zoom",after["yaw"]!=before["yaw"] and after["zoom"]==before["zoom"],before_yaw=before["yaw"],after_yaw=after["yaw"],zoom=after["zoom"])
    before=after;action("pan",x=.62,y=.43,dx=.1,dy=.03);time.sleep(.8);after=snapshot()
    check("one-finger camera pan",(before["camera_x"],before["camera_y"])!=(after["camera_x"],after["camera_y"]),before=[before["camera_x"],before["camera_y"]],after=[after["camera_x"],after["camera_y"]])
    start=len(log());action("hold",x=.85,y=.5);time.sleep(1.2);text=log()[start:]
    check("one-finger left-button hold", "AndroidInput action=3 " in text and "AndroidInput action=4 " in text)
    start=len(log());action("twohold",x=.85,y=.5);time.sleep(1.2);text=log()[start:]
    check("two-finger right-button hold", "AndroidInput action=5 " in text and "AndroidInput action=6 " in text)
    report["all_passed"]=all(c["passed"] for c in report["checks"])
    (ROOT/"build/native-smoke/gesture-results.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report,indent=2))
    if not report["all_passed"]:raise SystemExit(1)


if __name__=="__main__":main()
