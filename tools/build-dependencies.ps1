[CmdletBinding()]
param([string]$BuildRoot = "$env:USERPROFILE/kfx-build")
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$sdkRoot = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { "$env:LOCALAPPDATA/Android/Sdk" }
$ndkRoot = Join-Path $sdkRoot 'ndk/27.3.13750724'
if (-not (Test-Path "$ndkRoot/build/cmake/android.toolchain.cmake")) { throw "Missing Android NDK: $ndkRoot" }
$env:ANDROID_NDK_HOME = $ndkRoot
$env:ANDROID_NDK_ROOT = $ndkRoot
$env:VCPKG_MAX_CONCURRENCY = '12'
New-Item -ItemType Directory -Force -Path $BuildRoot | Out-Null
$vcpkg = Join-Path $projectRoot 'third_party/vcpkg/vcpkg.exe'
& $vcpkg install "--triplet=arm64-android-kfx" "--overlay-triplets=$projectRoot/native/triplets" "--overlay-ports=$projectRoot/native/ports" "--x-manifest-root=$projectRoot/native" "--x-install-root=$BuildRoot/installed" "--x-buildtrees-root=$BuildRoot/buildtrees" "--x-packages-root=$BuildRoot/packages" "--downloads-root=$BuildRoot/downloads" --disable-metrics
if ($LASTEXITCODE -ne 0) { throw "Native dependency build failed: $LASTEXITCODE" }
