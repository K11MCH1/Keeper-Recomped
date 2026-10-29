"""Build the Keeper Recomped APK without Gradle.

javac -> d8 -> aapt2 -> zipalign -> apksigner, like the Badman port. Inputs:
  android/launcher/   manifest, res/, java/, assets/, native/kr7z.c  (Claude)
  android/game/       java/, res/, assets/ (optional)                (Codex)
  build/android/native/arm64-v8a/*.so  engine libraries             (Codex)
The APK never contains Dungeon Keeper data: the launcher imports the player's own
files and downloads KeeperFX's data from keeperfx.net.

  python scripts/android/build_apk.py --output build/apk/keeper-recomped.apk [--debuggable]
         [--package-id org.keeperrecomped.app.dev] [--native-dir DIR] [--allow-no-engine]
"""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
LAUNCHER = ROOT / 'android/launcher'
GAME = ROOT / 'android/game'
LZMA = ROOT / 'third_party/lzma-sdk/C'
LZMA_SOURCES = ['Bcj2.c', 'Bra.c', 'Bra86.c', 'BraIA64.c', 'CpuArch.c', 'Delta.c', 'Lzma2Dec.c', 'LzmaDec.c',
                'Ppmd7.c', 'Ppmd7Dec.c', '7zCrc.c', '7zCrcOpt.c', '7zAlloc.c', '7zArcIn.c', '7zBuf.c', '7zBuf2.c',
                '7zDec.c', '7zFile.c', '7zStream.c']
LZMA_URL = 'https://codeload.github.com/ip7z/7zip/tar.gz/refs/tags/25.01'
LZMA_SHA256 = '8772e4ef86540c98bf4c23a6d9e13b3a25794d4bb30986ef0b1b9f20075b34c9'
ABI, TRIPLE = 'arm64-v8a', 'aarch64-linux-android'
MIN_SDK, TARGET_SDK = 35, 35
EXE = '.exe' if os.name == 'nt' else ''
LOCAL = Path(os.environ.get('LOCALAPPDATA', Path.home() / 'AppData/Local'))


class BuildError(Exception):
    pass


def digest(path):
    with Path(path).open('rb') as f:
        return hashlib.file_digest(f, 'sha256').hexdigest()


def run(args, log):
    r = subprocess.run([str(a) for a in args], capture_output=True, text=True, encoding='utf-8', errors='replace')
    log.write('$ ' + ' '.join(str(a) for a in args) + '\n' + r.stdout + r.stderr + '\n')
    if r.returncode:
        raise BuildError(f'{Path(str(args[0])).name} failed ({r.returncode}): {(r.stdout + r.stderr).strip()[-1500:]}')
    return r.stdout


def build_kr7z(llvm, work, log):
    if not (LZMA / '7zDec.c').is_file():
        raise BuildError(f'LZMA SDK missing at {LZMA}. Fetch {LZMA_URL} (sha256 {LZMA_SHA256}) and extract '
                         f'its C/ directory to third_party/lzma-sdk/C')
    out = work / 'libkr7z.so'
    run([llvm / f'bin/clang{EXE}', f'--target={TRIPLE}{MIN_SDK}', '-shared', '-fPIC', '-O2', '-fvisibility=hidden',
         '-DZ7_PPMD_SUPPORT', '-DZ7_EXTRACT_ONLY', f'-I{LZMA}', LAUNCHER / 'native/kr7z.c',
         *[LZMA / s for s in LZMA_SOURCES], '-llog', '-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1', '-o', out], log)
    return out


def engine_info(work, engine):
    """Which KeeperFX source the packaged engine was built from, so the launcher can tell
    when downloaded alpha data no longer matches it. An optional engine.json next to the
    libraries (Codex) may add "version"."""
    info = {'built': bool(engine)}
    src = ROOT / 'third_party/keeperfx'
    if (src / '.git').exists():
        r = subprocess.run(['git', '-C', str(src), 'log', '-1', '--format=%H%n%s'], capture_output=True, text=True)
        if r.returncode == 0 and '\n' in r.stdout:
            info['commit'], info['title'] = r.stdout.strip().split('\n', 1)
        r = subprocess.run(['git', '-C', str(src), 'status', '--porcelain', '--untracked-files=no'], capture_output=True, text=True)
        info['android_patches'] = r.returncode == 0 and bool(r.stdout.strip())
        ver = {}
        mk = src / 'version.mk'
        if mk.is_file():
            for line in mk.read_text(encoding='utf-8').splitlines():
                m = re.match(r'(VER_\w+)=(\d+)', line)
                if m:
                    ver[m.group(1)] = m.group(2)
            info['version'] = '.'.join(ver.get(k, '0') for k in ('VER_MAJOR', 'VER_MINOR', 'VER_RELEASE'))
    extra = ROOT / 'build/android/native/engine.json'
    if extra.is_file():
        info.update(json.loads(extra.read_text(encoding='utf-8')))
    out = work / 'engine.json'
    out.write_text(json.dumps(info, indent=1) + '\n', encoding='utf-8')
    return out


def needed(llvm, lib, log):
    dyn = run([llvm / f'bin/llvm-readelf{EXE}', '--dynamic', lib], log)
    return re.findall(r'\(NEEDED\).*?\[(.*?)\]', dyn)


COMPILER_SKIP = ('libclang_rt.', 'libarcher', 'libLTO', 'libRemarks', 'libclang.so', 'libomp')


def compiler_payload(args, llvm, log):
    """Termux's Clang and LLD for the phone (the Badman launcher's packaging), each file
    checked against the signed package index before it goes into the APK."""
    manifest = json.loads(args.compiler_manifest.read_text(encoding='utf-8'))
    if not str(manifest.get('status', '')).startswith('PASS_SIGNED_INDEX'):
        raise BuildError('The compiler package manifest is not a verified signed-index extraction')
    packages = manifest['packages']
    base = args.compiler_packages / 'aarch64'

    def verified(path):
        relative = path.relative_to(base)
        key = '/'.join(relative.parts[1:])
        row = packages.get(f"aarch64/{relative.parts[0].removesuffix('-files')}", {}).get('files', {}).get(key)
        if not row or digest(path) != row['sha256']:
            raise BuildError(f'Compiler package file does not match the signed index: {relative}')
        return path

    libraries, aliases = [], {}
    for path in sorted(base.glob('*-files/lib/*')):
        if not path.is_file() or not (path.name.endswith('.so') or '.so.' in path.name) or path.name.startswith(COMPILER_SKIP):
            continue
        verified(path)
        dynamic = run([llvm / f'bin/llvm-readelf{EXE}', '--dynamic', path], log)
        match = re.search(r'\(SONAME\).*?\[(.*?)\]', dynamic)
        soname = match.group(1) if match else path.name
        # Versioned names cannot be APK libraries, and Termux's libc++ must not replace the
        # NDK one the game uses: those get a private name the phone links the soname to.
        if path.name.endswith('.so') and path.name != 'libc++_shared.so':
            name = path.name
        else:
            name = 'libkr_dep_' + re.sub(r'[^A-Za-z0-9]+', '_', soname.removeprefix('lib').split('.so')[0]) + '.so'
        if name != soname:
            aliases[soname] = name
        libraries.append((path, name))
    for package, binary, name in (('clang', 'clang-21', 'libkr_clang_tool.so'), ('lld', 'lld', 'libkr_lld_tool.so')):
        libraries.append((verified(base / f'{package}-files/bin/{binary}'), name))
    notices = [(verified(p), 'assets/licenses/compiler/' + p.relative_to(base).as_posix())
               for p in sorted(base.glob('*-files/share/doc/**/*'))
               if p.is_file() and any(k in p.name.lower() for k in ('license', 'copying', 'copyright', 'notice'))]
    return libraries, aliases, notices


def build(args):
    sdk, ndk, jdk = args.sdk, args.ndk, args.jdk
    platform = sdk / f'platforms/android-{TARGET_SDK}/android.jar'
    tools = sorted((sdk / 'build-tools').glob('*'))
    if not platform.is_file() or not tools:
        raise BuildError(f'Android SDK platform {TARGET_SDK} and build-tools are required under {sdk}')
    bt = tools[-1]
    llvm = next((ndk / 'toolchains/llvm/prebuilt').glob('*'), None)
    if llvm is None:
        raise BuildError(f'NDK toolchain not found under {ndk}')
    work = args.output.parent / (args.output.stem + '-work')
    if work.exists():
        shutil.rmtree(work)
    for d in ('classes', 'dex', 'lib'):
        (work / d).mkdir(parents=True)
    log = (work / 'build.log').open('w', encoding='utf-8')

    # Native libraries: the launcher's 7z helper plus Codex's engine.
    libs = [(build_kr7z(llvm, work, log), 'libkr7z.so')]
    engine = sorted(args.native_dir.glob('*.so')) if args.native_dir.is_dir() else []
    if not engine and not args.allow_no_engine:
        raise BuildError(f'No engine libraries in {args.native_dir}; pass --allow-no-engine for a launcher-only build')
    libs += [(p, p.name) for p in engine]
    if len({n for _, n in libs}) != len(libs):
        raise BuildError('Two native libraries share one name')
    stripped = []
    for src, name in libs:
        dst = work / 'lib' / name
        run([llvm / f'bin/llvm-strip{EXE}', '--strip-unneeded', '-o', dst, src], log)
        stripped.append((dst, name))
    names = {n for _, n in stripped}
    if any('libc++_shared.so' in needed(llvm, p, log) for p, _ in stripped) and 'libc++_shared.so' not in names:
        stripped.append((llvm / f'sysroot/usr/lib/{TRIPLE}/libc++_shared.so', 'libc++_shared.so'))

    # The phone-side engine compiler (scripts/android/engine_kit.py output + Termux Clang/LLD).
    compiler_assets = []
    if not args.no_engine_compiler:
        kit = args.engine_kit
        missing = [n for n in ('libkrdeps.so', 'engine-kit.zip', 'toolchain.zip') if not (kit / n).is_file()]
        if missing:
            raise BuildError(f'Engine kit incomplete in {kit} ({", ".join(missing)}); run scripts/android/engine_kit.py'
                             ' or pass --no-engine-compiler')
        # Phone builds compile the kit's copy of our Android engine code: it must be current.
        with zipfile.ZipFile(kit / 'engine-kit.zip') as bundle:
            stale = [n for n in bundle.namelist() if n.startswith('native/')
                     and bundle.read(n) != (ROOT / 'native/src' / n.removeprefix('native/')).read_bytes()]
        if stale:
            raise BuildError(f'The engine kit is older than native/src ({", ".join(stale)}); run scripts/android/engine_kit.py')
        stripped.append((kit / 'libkrdeps.so', 'libkrdeps.so'))
        compiler, aliases, notices = compiler_payload(args, llvm, log)
        stripped += compiler
        kit_id = json.loads(zipfile.ZipFile(kit / 'engine-kit.zip').read('kit.json')).get('kit_id', '')
        description = work / 'compiler.json'
        description.write_text(json.dumps({'aliases': aliases, 'kit_id': kit_id}, indent=1), encoding='utf-8')
        compiler_assets = [(kit / 'toolchain.zip', 'assets/toolchain.zip'), (kit / 'engine-kit.zip', 'assets/engine-kit.zip'),
                           (description, 'assets/compiler.json')] + notices

    # Resources and manifest.
    manifest = LAUNCHER / 'AndroidManifest.xml'
    text = manifest.read_text(encoding='utf-8')
    if args.package_id:
        suffix = args.package_id.rsplit('.', 1)[-1]
        text = text.replace('android:label="Keeper Recomped"', f'android:label="Keeper Recomped ({suffix})"')
    manifest = work / 'AndroidManifest.xml'
    manifest.write_text(text, encoding='utf-8')
    flats = []
    for res in (LAUNCHER / 'res', GAME / 'res'):
        if res.is_dir():
            out = work / f'res-{res.parent.name}.zip'
            run([bt / f'aapt2{EXE}', 'compile', '--dir', res, '-o', out], log)
            flats.append(out)
    base = work / 'base.apk'
    link = [bt / f'aapt2{EXE}', 'link', '--manifest', manifest, '-I', platform, '--min-sdk-version', MIN_SDK,
            '--target-sdk-version', TARGET_SDK, '--java', work / 'gen', '--custom-package', 'org.keeperrecomped.launcher',
            '--extra-packages', 'org.keeperrecomped.game', '-o', base, *flats]
    if args.package_id:
        link += ['--rename-manifest-package', args.package_id]
    if args.debuggable:
        link.append('--debug-mode')
    run(link, log)
    # Java: launcher + game, with the R classes aapt2 generated.
    sources = sorted((LAUNCHER / 'java').rglob('*.java')) + sorted((GAME / 'java').rglob('*.java'))
    sources += sorted((work / 'gen').rglob('*.java'))
    if args.touch_probe:
        # Codex's shell-only gesture injector (GameActivity loads it when started with enable_touch_probe).
        sources.append(ROOT / 'native/smoke/java/org/keeperrecomped/game/TouchProbe.java')
    jars = sorted((GAME / 'libs').glob('*.jar')) if (GAME / 'libs').is_dir() else []
    classpath = os.pathsep.join(str(p) for p in [bt / 'core-lambda-stubs.jar', *jars])
    run([jdk / f'bin/javac{EXE}', '-source', '8', '-target', '8', '-encoding', 'UTF-8', '-Xlint:-options', '-nowarn',
         '-bootclasspath', platform, '-classpath', classpath, '-d', work / 'classes', *sources], log)
    run([jdk / f'bin/java{EXE}', '-cp', bt / 'lib/d8.jar', 'com.android.tools.r8.D8',
         '--debug' if args.debuggable else '--release', '--min-api', MIN_SDK, '--lib', platform,
         '--output', work / 'dex', *sorted((work / 'classes').rglob('*.class')), *jars], log)

    # Assets and licences.
    extra = [(engine_info(work, engine), 'assets/engine.json')]
    for assets in (LAUNCHER / 'assets', GAME / 'assets'):
        if assets.is_dir():
            for p in sorted(assets.rglob('*')):
                if p.is_file():
                    extra.append((p, 'assets/' + p.relative_to(assets).as_posix()))
    for src, name in ((ROOT / 'third_party/lzma-sdk/DOC/License.txt', 'LZMA-SDK-License.txt'),
                      (ROOT / 'third_party/keeperfx/LICENSE', 'KeeperFX-GPL-2.0.txt'),
                      (ndk / 'NOTICE', 'Android-NDK-NOTICE.txt')):
        if src.is_file():
            extra.append((src, 'assets/licenses/' + name))
    extra += compiler_assets
    if len({n for _, n in extra}) != len(extra):
        raise BuildError('Two assets share one path')

    unsigned = work / 'unsigned.apk'
    shutil.copy2(base, unsigned)
    contents = []
    dexes = sorted((work / 'dex').glob('classes*.dex'))
    with zipfile.ZipFile(unsigned, 'a') as apk:
        for dex in dexes:
            apk.write(dex, dex.name, compress_type=zipfile.ZIP_DEFLATED)
        for src, name in stripped:
            apk.write(src, f'lib/{ABI}/{name}', compress_type=zipfile.ZIP_DEFLATED)
            contents.append({'path': f'lib/{ABI}/{name}', 'sha256': digest(src), 'bytes': src.stat().st_size})
        for src, name in extra:
            # The kit archives are compressed already.
            apk.write(src, name, compress_type=zipfile.ZIP_STORED if name.endswith('.zip') else zipfile.ZIP_DEFLATED)
    aligned = work / 'aligned.apk'
    run([bt / f'zipalign{EXE}', '-P', '16', '-f', '4', unsigned, aligned], log)
    keystore = args.keystore or ROOT / 'build/android/keeper-recomped-debug.keystore'
    keystore.parent.mkdir(parents=True, exist_ok=True)
    if not keystore.exists():
        if args.keystore:
            raise BuildError(f'Keystore not found: {keystore}')
        run([jdk / f'bin/keytool{EXE}', '-genkeypair', '-keystore', keystore, '-storepass', 'android', '-keypass', 'android',
             '-alias', 'androiddebugkey', '-dname', 'CN=Keeper Recomped local build', '-validity', '10000',
             '-keyalg', 'RSA', '-keysize', '2048'], log)
    run([jdk / f'bin/java{EXE}', '-jar', bt / 'lib/apksigner.jar', 'sign', '--ks', keystore,
         '--ks-pass', 'pass:' + args.keystore_password, '--key-pass', 'pass:' + args.keystore_password,
         '--out', args.output, aligned], log)
    run([jdk / f'bin/java{EXE}', '-jar', bt / 'lib/apksigner.jar', 'verify', args.output], log)
    log.close()
    report = {'apk': str(args.output), 'sha256': digest(args.output), 'bytes': args.output.stat().st_size,
              'abi': ABI, 'min_sdk': MIN_SDK, 'target_sdk': TARGET_SDK, 'debuggable': args.debuggable,
              'package_id': args.package_id or 'org.keeperrecomped.app', 'engine_libraries': [p.name for p in engine],
              'game_activity_java': (GAME / 'java/org/keeperrecomped/game/GameActivity.java').is_file(),
              'native': contents, 'game_data': False}
    args.output.with_suffix('.json').write_text(json.dumps(report, indent=1) + '\n', encoding='utf-8')
    print(json.dumps({k: report[k] for k in ('apk', 'bytes', 'engine_libraries', 'game_activity_java')}))


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--output', type=Path, required=True)
    p.add_argument('--debuggable', action='store_true', help='Local engineering build (adb run-as)')
    p.add_argument('--package-id', help='Install beside the main app under this application id')
    p.add_argument('--touch-probe', action='store_true', help='Debug only: include the adb gesture injector (TouchProbe)')
    termux = Path(os.environ.get('KR_TERMUX_PACKAGES', Path.home() / 'Desktop/PSPrecomp/out/android-port-v1/compiler-probe'))
    p.add_argument('--no-engine-compiler', action='store_true', help='Leave out the phone-side engine compiler (smaller APK)')
    p.add_argument('--engine-kit', type=Path, default=ROOT / 'build/engine-kit', help='scripts/android/engine_kit.py output')
    p.add_argument('--compiler-packages', type=Path, default=termux / 'termux-packages', help='Extracted Termux clang/lld packages')
    p.add_argument('--compiler-manifest', type=Path, default=termux / 'COMPILER_PACKAGES.json', help='Signed-index verification of them')
    p.add_argument('--native-dir', type=Path, default=ROOT / f'build/android/native/{ABI}')
    p.add_argument('--allow-no-engine', action='store_true', help='Launcher-only build without engine libraries')
    p.add_argument('--keystore', type=Path)
    p.add_argument('--keystore-password', default='android')
    p.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_SDK_ROOT', LOCAL / 'Android/Sdk')))
    p.add_argument('--ndk', type=Path, default=Path(os.environ.get('ANDROID_NDK_ROOT', LOCAL / 'Android/Sdk/ndk/27.3.13750724')))
    p.add_argument('--jdk', type=Path, default=Path(os.environ.get('KR_JDK', LOCAL / 'Programs/DroidDeckJDK17/jdk-17.0.20.1+1')))
    args = p.parse_args()
    if args.touch_probe and not args.debuggable:
        p.error('--touch-probe is only for --debuggable builds')
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    try:
        build(args)
    except BuildError as e:
        print('APK build failed: ' + str(e), file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
