$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$sdkRoot=if($env:ANDROID_HOME){$env:ANDROID_HOME}else{"$env:LOCALAPPDATA/Android/Sdk"}
$compiler=Join-Path $sdkRoot 'ndk/27.3.13750724/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe'
$lua="$projectRoot/third_party/lua-5.1.5/src"
$sources=Get-ChildItem -LiteralPath $lua -Filter '*.c' | Where-Object {$_.Name -notin @('lua.c','luac.c','print.c')} | ForEach-Object {$_.FullName}
$binary="$projectRoot/build/tests/keeper-lua-tests"
& py -3.13 "$PSScriptRoot/patch-lua.py"
& $compiler --target=aarch64-linux-android35 -std=gnu11 -O2 -D_FORTIFY_SOURCE=2 -fstack-protector-strong -DLUA_USE_POSIX -DLUA_USE_DLOPEN "-I$lua" "-I$projectRoot/native/src" @sources "$projectRoot/third_party/LuaBitOp-1.0.4/bit.c" "$projectRoot/native/src/LuaCompat.c" "$projectRoot/native/tests/lua_tests.c" -lm -ldl -o $binary
if($LASTEXITCODE -ne 0){throw 'Lua tests failed to compile'}
& adb push $binary /data/local/tmp/keeper-tests/
& adb push "$projectRoot/KeeperFX/fxdata/lua/external/binser.lua" /data/local/tmp/keeper-tests/binser.lua
& adb shell chmod 755 /data/local/tmp/keeper-tests/keeper-lua-tests
& adb shell /data/local/tmp/keeper-tests/keeper-lua-tests /data/local/tmp/keeper-tests/binser.lua
if($LASTEXITCODE -ne 0){throw 'Lua tests failed on Android'}
