$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$sdkRoot=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{"$env:LOCALAPPDATA/Android/Sdk"}
$compiler=Join-Path $sdkRoot 'ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin/clang++.exe'
New-Item -ItemType Directory -Force -Path "$projectRoot/build/tests" | Out-Null
$binary="$projectRoot/build/tests/touch-tests"
& $compiler --target=aarch64-linux-android35 -std=c++20 -O2 -static-libstdc++ "-I$projectRoot/native/src" "$projectRoot/native/src/TouchGestures.cpp" "$projectRoot/native/tests/touch_tests.cpp" -o $binary
if($LASTEXITCODE -ne 0){throw 'Touch tests failed to compile'}
& adb push $binary /data/local/tmp/keeper-touch-tests
if($LASTEXITCODE -ne 0){throw 'Touch test upload failed'}
& adb shell chmod 755 /data/local/tmp/keeper-touch-tests
& adb shell /data/local/tmp/keeper-touch-tests
if($LASTEXITCODE -ne 0){throw 'Touch gesture test failed on Android'}
