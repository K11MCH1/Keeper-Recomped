"""Engine kit: what the phone needs to compile the KeeperFX engine itself.

The PC engine build (tools/build-native.ps1) stays the reference. From its Ninja
file this script takes the exact compile flags and link inputs, then produces:

  build/engine-kit/libkrdeps.so   the version-independent libraries (SDL3_image/mixer,
                                  OpenAL, curl, FFmpeg, OpenSSL, Lua, ...) prelinked
                                  whole, so newer KeeperFX sources can use more of them
  build/engine-kit/engine-kit.zip headers, our Android sources, the KeeperFX source
                                  seams (tools/patch-keeperfx.py --emit-seams) and
                                  kit.json, the phone's compile/link recipe
  build/engine-kit/toolchain.zip  NDK sysroot headers and API-35 link stubs, compiler-rt
                                  builtins, libunwind/libatomic and Clang's resource headers

The phone downloads a KeeperFX source revision, applies the seams and compiles
src/**/*.c|cpp plus deps/centitoml and deps/glad (they ship with the source) and
the files in native/, then links against libkrdeps.so, libSDL3.so and libc++_shared.so.

  python scripts/android/engine_kit.py [--ninja ~/kfx-build/engine/build.ninja]
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
LOCAL = Path(os.environ.get('LOCALAPPDATA', Path.home() / 'AppData/Local'))
OUT = ROOT / 'build/engine-kit'
API = 35
TRIPLE = 'aarch64-linux-android'
# KeeperFX's own deps compiled with the source (they change with it).
SOURCE_LIBS = {'libcentitoml.a', 'libglad.a'}
NATIVE_SOURCES = ['AndroidEntry.cpp', 'TouchGestures.cpp', 'TouchBridge.cpp']
NATIVE_HEADERS = ['AndroidRuntime.hpp', 'TouchGestures.hpp', 'LuaCompat.h']
# Bump when the phone's compile/link recipe changes in a way that needs rebuilt engines.
KIT_VERSION = 1


class KitError(Exception):
    pass


def run(args, **kw):
    result = subprocess.run([str(a) for a in args], capture_output=True, text=True, **kw)
    if result.returncode:
        raise KitError(f"{Path(str(args[0])).name} failed:\n{result.stdout[-3000:]}{result.stderr[-3000:]}")
    return result.stdout


def ninja_block(ninja, predicate):
    text = ninja.read_text(encoding='utf-8')
    for block in text.split('\n\n'):
        if block.startswith('build ') and predicate(block.split('\n')[0]):
            values = {}
            for line in block.split('\n')[1:]:
                key, _, value = line.strip().partition(' = ')
                values[key] = value
            return values
    raise KitError(f'No matching build statement in {ninja}')


def split_flags(text):
    """Ninja flag strings: whitespace separated, double quotes group paths with spaces."""
    return [part.strip('"') for part in re.findall(r'"[^"]*"|\S+', text)]


def recipe(ninja, build_dir):
    c = ninja_block(ninja, lambda first: '/keeperfx.dir/' in first and first.split(':')[0].endswith('player_data.c.o'))
    cxx = ninja_block(ninja, lambda first: '/keeperfx.dir/' in first and first.split(':')[0].endswith('TouchBridge.cpp.o'))
    link = ninja_block(ninja, lambda first: 'libkeeperfx.so:' in first)
    drop = {'-g', '-O3', '-no-canonical-prefixes'}

    def flags(values, std):
        out, skip = [], False
        for flag in split_flags(values['FLAGS']):
            if skip:
                skip = False
                continue
            if flag == '-include':
                skip = True  # re-added with the kit's path
                continue
            if flag in drop or flag.startswith('-std='):
                continue
            out.append(flag)
        if std not in split_flags(values['FLAGS']):
            raise KitError(f'Expected {std} in the PC build flags')
        return out
    defines = split_flags(c['DEFINES'])
    if split_flags(cxx['DEFINES']) != defines:
        raise KitError('C and C++ objects use different defines')
    libraries = split_flags(link['LINK_LIBRARIES'])
    archives = []
    for token in libraries:
        if token.endswith('.a'):
            path = Path(token) if Path(token).is_absolute() else build_dir / token
            if path.name in SOURCE_LIBS:
                continue
            if not path.is_file():
                raise KitError(f'Missing link input {path}')
            if path not in archives:
                archives.append(path)
    return {'c': flags(c, '-std=gnu11'), 'cxx': flags(cxx, '-std=gnu++20'), 'defines': defines}, archives


def build_deps(ndk, archives, sdl3, work):
    """All version-independent libraries, whole, as one shared library the phone links against."""
    llvm = ndk / 'toolchains/llvm/prebuilt/windows-x86_64'
    exe = '.exe' if os.name == 'nt' else ''
    out = work / 'libkrdeps.so'
    args = [llvm / f'bin/clang++{exe}', f'--target={TRIPLE}{API}', '-shared', '-o', out, '-Wl,-soname,libkrdeps.so',
            '-Wl,--whole-archive', *archives, '-Wl,--no-whole-archive', sdl3,
            '-Wl,-z,max-page-size=16384', '-Wl,--build-id=sha1', '-Wl,--no-undefined', '-Wl,-z,relro', '-Wl,-z,now',
            '-lc++_shared', '-landroid', '-lmediandk', '-llog', '-ldl', '-lm', '-latomic']
    run(args)
    run([llvm / f'bin/llvm-strip{exe}', '--strip-unneeded', out])
    return out


def write_tree(bundle, directory, prefix, suffixes=None):
    for path in sorted(directory.rglob('*')):
        if path.is_file() and (suffixes is None or path.suffix.lower() in suffixes):
            bundle.write(path, prefix + path.relative_to(directory).as_posix())


def kit_archive(flags, vcpkg, work):
    seams = work / 'seams.json'
    run([sys.executable, ROOT / 'tools/patch-keeperfx.py', '--emit-seams', seams])
    third = ROOT / 'third_party'
    engine = json.loads((ROOT / 'build/android/native/engine.json').read_text(encoding='utf-8')) \
        if (ROOT / 'build/android/native/engine.json').is_file() else {}
    kit = {
        'kit': KIT_VERSION,
        'api': API,
        'bundled_commit': engine.get('keeperfx_commit', ''),
        'c': flags['c'], 'cxx': flags['cxx'], 'defines': flags['defines'],
        # Same order as the PC build; @SRC@ = patched KeeperFX source, @GEN@ = generated, @KIT@ = this kit.
        'includes': ['@SRC@/src', '@GEN@', '@KIT@/native', '@KIT@/include/enet6', '@KIT@/include/centijson',
                     '@SRC@/deps/centitoml', '@KIT@/include/astronomy', '@KIT@/include/lua', '@SRC@/deps/glad/include'],
        'system_includes': ['@KIT@/include/vcpkg', '@KIT@/include/vcpkg/AL'],
        'force_include': '@KIT@/native/LuaCompat.h',
        'sources': {'roots': ['src'], 'exclude': ['/ftests/', '/PlatformWindows\\.cpp$', '/WindowCompositorWin\\.cpp$'],
                    'extra': ['deps/centitoml/toml_api.c', 'deps/glad/src/glad.c'], 'native': NATIVE_SOURCES},
        'link': ['libkrdeps.so', 'libSDL3.so', 'libc++_shared.so'],
        'system_libs': ['android', 'nativewindow', 'log', 'mediandk', 'dl', 'm'],
        'generated': {'ver_defs': 'ver_defs.h.in', 'icon': 'res/keeperfx_icon256-24bpp.png'},
    }
    # Identifies what a phone-built engine contains besides KeeperFX itself: when an app update
    # changes it, the launcher asks for a rebuild.
    digest = hashlib.sha256(json.dumps(kit, sort_keys=True).encode('utf-8') + seams.read_bytes())
    for name in NATIVE_SOURCES + NATIVE_HEADERS:
        digest.update((ROOT / 'native/src' / name).read_bytes())
    kit['kit_id'] = digest.hexdigest()[:16]
    archive = work / 'engine-kit.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        bundle.writestr('kit.json', json.dumps(kit, indent=1))
        bundle.write(seams, 'seams.json')
        for name in NATIVE_SOURCES + NATIVE_HEADERS:
            bundle.write(ROOT / 'native/src' / name, 'native/' + name)
        write_tree(bundle, vcpkg / 'include', 'include/vcpkg/')
        write_tree(bundle, third / 'enet6/include', 'include/enet6/')
        write_tree(bundle, third / 'centijson/src', 'include/centijson/', {'.h'})
        write_tree(bundle, third / 'astronomy/source/c', 'include/astronomy/', {'.h'})
        write_tree(bundle, third / 'lua-5.1.5/src', 'include/lua/', {'.h'})
    return archive, kit


def toolchain_archive(ndk, compiler_packages, work):
    llvm = ndk / 'toolchains/llvm/prebuilt/windows-x86_64'
    sysroot = llvm / 'sysroot'
    builtins = sorted(llvm.glob('lib/clang/*/lib/linux/libclang_rt.builtins-aarch64-android.a'))
    if not builtins:
        raise KitError('NDK compiler-rt builtins not found')
    runtime = builtins[-1].parent / 'aarch64'
    resource = sorted((compiler_packages / 'aarch64/clang-files/lib/clang').glob('*/include'))
    if not resource:
        raise KitError(f'Clang resource headers not found under {compiler_packages}')
    archive = work / 'toolchain.zip'
    with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as bundle:
        write_tree(bundle, sysroot / 'usr/include', 'sysroot/usr/include/')
        write_tree(bundle, sysroot / 'usr/lib' / TRIPLE / str(API), f'sysroot/usr/lib/{TRIPLE}/{API}/')
        bundle.write(builtins[-1], 'builtins/' + builtins[-1].name)
        for name in ('libunwind.a', 'libatomic.a'):
            bundle.write(runtime / name, 'builtins/' + name)
        write_tree(bundle, resource[-1], 'resource/include/')
        bundle.writestr('toolchain.json', json.dumps({'target': f'{TRIPLE}{API}', 'triple': TRIPLE, 'api': API,
                                                      'builtins': 'builtins/' + builtins[-1].name}, indent=1))
    return archive


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument('--ninja', type=Path, default=Path.home() / 'kfx-build/engine/build.ninja')
    p.add_argument('--vcpkg', type=Path, default=Path.home() / 'kfx-build/installed/arm64-android-kfx')
    p.add_argument('--ndk', type=Path, default=LOCAL / 'Android/Sdk/ndk/27.3.13750724')
    p.add_argument('--compiler-packages', type=Path,
                   default=Path(os.environ.get('KR_TERMUX_PACKAGES', Path.home() / 'Desktop/PSPrecomp/out/android-port-v1/compiler-probe/termux-packages')))
    args = p.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    flags, archives = recipe(args.ninja, args.ninja.parent)
    deps = build_deps(args.ndk, archives, args.vcpkg / 'lib/libSDL3.so', OUT)
    kit, info = kit_archive(flags, args.vcpkg, OUT)
    tools = toolchain_archive(args.ndk, args.compiler_packages, OUT)
    for path in (deps, kit, tools):
        print(f'{path.relative_to(ROOT)}  {path.stat().st_size / 1048576:.1f} MiB')
    print(f"{len(archives)} libraries prelinked; {len(info['c'])} C flags, {len(info['defines'])} defines")


if __name__ == '__main__':
    try:
        main()
    except KitError as error:
        sys.exit(f'engine_kit: {error}')
