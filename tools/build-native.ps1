[CmdletBinding()]
param([string]$BuildRoot="$env:USERPROFILE/kfx-build", [switch]$ConfigureOnly)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$sdkRoot=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{"$env:LOCALAPPDATA/Android/Sdk"}
$ndkRoot=Join-Path $sdkRoot 'ndk/27.3.13750724'
$cmake=Join-Path $sdkRoot 'cmake/3.22.1/bin/cmake.exe'
$ninja=Join-Path $sdkRoot 'cmake/3.22.1/bin/ninja.exe'
& py -3.13 "$PSScriptRoot/patch-keeperfx.py"
if($LASTEXITCODE -ne 0){throw 'KeeperFX patch failed'}
& py -3.13 "$PSScriptRoot/patch-lua.py"
if($LASTEXITCODE -ne 0){throw 'Lua portability patch failed'}
$env:ANDROID_NDK_HOME=$ndkRoot
$nativeBuild=Join-Path $BuildRoot 'engine'
$arguments=@('-S',"$projectRoot/native",'-B',$nativeBuild,'-G','Ninja',"-DCMAKE_MAKE_PROGRAM=$ninja",'-DCMAKE_BUILD_TYPE=Release',"-DCMAKE_TOOLCHAIN_FILE=$projectRoot/third_party/vcpkg/scripts/buildsystems/vcpkg.cmake", "-DVCPKG_CHAINLOAD_TOOLCHAIN_FILE=$ndkRoot/build/cmake/android.toolchain.cmake",'-DVCPKG_TARGET_TRIPLET=arm64-android-kfx',"-DVCPKG_INSTALLED_DIR=$BuildRoot/installed",'-DVCPKG_MANIFEST_INSTALL=OFF',"-DVCPKG_OVERLAY_TRIPLETS=$projectRoot/native/triplets",'-DANDROID_ABI=arm64-v8a','-DANDROID_PLATFORM=android-35','-DANDROID_STL=c++_shared','-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON')
& $cmake @arguments
if($LASTEXITCODE -ne 0){throw 'Native configure failed'}
if($ConfigureOnly){exit 0}
& $cmake --build $nativeBuild --parallel 12
if($LASTEXITCODE -ne 0){throw 'Native engine build failed'}
$destination=Join-Path $projectRoot 'build/android/native/arm64-v8a'
Copy-Item -LiteralPath "$BuildRoot/installed/arm64-android-kfx/lib/libSDL3.so" -Destination $destination
Copy-Item -LiteralPath "$ndkRoot/toolchains/llvm/prebuilt/windows-x86_64/sysroot/usr/lib/aarch64-linux-android/libc++_shared.so" -Destination $destination
Write-Output "ARM64 native libraries: $destination"
