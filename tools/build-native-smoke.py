"""Build an isolated debuggable SDL engine APK without touching the main launcher.

Uses installed platform headers (35 preferred, 34 sufficient for these Java APIs),
while the manifest always requires Android15/API35. No proprietary data is bundled.
"""
from pathlib import Path
import json
import os
import shutil
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]


def main():
    sdk=Path(os.environ.get("ANDROID_HOME",str(Path.home()/"AppData/Local/Android/Sdk")))
    jdk=Path(os.environ.get("JAVA_HOME",str(Path.home()/"AppData/Local/Programs/DroidDeckJDK17/jdk-17.0.20.1+1")))
    ndk=sdk/"ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin"
    bt=sdk/"build-tools/35.0.0"
    api=35 if (sdk/"platforms/android-35/android.jar").exists() else 34
    platform=sdk/f"platforms/android-{api}/android.jar"
    work=ROOT/"build/native-smoke"
    for p in (work,work/"classes",work/"dex",work/"lib"):p.mkdir(parents=True,exist_ok=True)
    log=(work/"build.log").open("w",encoding="utf-8")
    def run(*args):
        result=subprocess.run([str(a) for a in args],capture_output=True,text=True,encoding="utf-8",errors="replace")
        log.write("$ "+" ".join(str(a) for a in args)+"\n"+result.stdout+result.stderr+"\n");log.flush()
        if result.returncode:raise RuntimeError(result.stderr[-2000:] or result.stdout[-2000:])
    sources=sorted((ROOT/"android/game/java").rglob("*.java"))
    run(jdk/"bin/javac.exe","-source","8","-target","8","-encoding","UTF-8","-Xlint:-options","-bootclasspath",platform,"-d",work/"classes",*sources)
    run(jdk/"bin/java.exe","-cp",bt/"lib/d8.jar","com.android.tools.r8.D8","--debug","--min-api","35","--lib",platform,"--output",work/"dex",*sorted((work/"classes").rglob("*.class")))
    run(bt/"aapt2.exe","link","-o",work/"base.apk","-I",platform,"--manifest",ROOT/"native/smoke/AndroidManifest.xml","--min-sdk-version","35","--target-sdk-version","35")
    unsigned=work/"unsigned.apk";shutil.copyfile(work/"base.apk",unsigned)
    libraries=[]
    with zipfile.ZipFile(unsigned,"a",compression=zipfile.ZIP_DEFLATED) as apk:
        for p in sorted((work/"dex").glob("*.dex")):apk.write(p,p.name)
        for p in sorted((ROOT/"build/android/native/arm64-v8a").glob("*.so")):
            destination=work/"lib"/p.name;shutil.copyfile(p,destination)
            run(ndk/"llvm-strip.exe","--strip-unneeded",destination)
            apk.write(destination,"lib/arm64-v8a/"+p.name);libraries.append(p.name)
    run(bt/"zipalign.exe","-P","16","-f","4",unsigned,work/"aligned.apk")
    keystore=work/"debug.keystore"
    if not keystore.exists():run(jdk/"bin/keytool.exe","-genkeypair","-keystore",keystore,"-storepass","android","-keypass","android","-alias","androiddebugkey","-dname","CN=Keeper Native Test","-keyalg","RSA","-keysize","2048","-validity","10000")
    apk=work/"keeper-native-test.apk"
    run(jdk/"bin/java.exe","-jar",bt/"lib/apksigner.jar","sign","--ks",keystore,"--ks-pass","pass:android","--key-pass","pass:android","--out",apk,work/"aligned.apk")
    run(jdk/"bin/java.exe","-jar",bt/"lib/apksigner.jar","verify",apk)
    report={"apk":str(apk),"package":"org.keeperrecomped.nativecheck","min_sdk":35,"target_sdk":35,"java_compile_api":api,"native_libraries":libraries,"bytes":apk.stat().st_size,"proprietary_assets":False}
    (work/"report.json").write_text(json.dumps(report,indent=2)+"\n")
    print(json.dumps(report));log.close()


if __name__=="__main__":main()
