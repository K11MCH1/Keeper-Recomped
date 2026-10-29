[CmdletBinding()]
param([string]$Jdk21='C:/Users/kimchi/Desktop/PSPrecomp/CE-Ghidra-MCP-Pack/runtime/jdk-21.0.12.1+1')
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$ghidraRoot=Join-Path $projectRoot 'CE-Ghidra-MCP-Pack/runtime/ghidra_12.1.3_PUBLIC'
$sourceRoot=Join-Path $projectRoot 'third_party/ghidra-lx-loader'
$gradleRoot=Join-Path $env:USERPROFILE '.gradle/wrapper/dists/gradle-8.10.2-all'
if(-not(Test-Path "$Jdk21/bin/java.exe")){throw 'Set -Jdk21 to the existing JDK21 installation'}
$gradle=(& rg --files $gradleRoot -g gradle.bat | Select-Object -First 1)
if(-not $gradle){throw 'Gradle 8.10.2 is required to build the pinned Ghidra extension'}
$env:JAVA_HOME=$Jdk21
$env:GHIDRA_INSTALL_DIR=$ghidraRoot
Push-Location $sourceRoot
try {
    & $gradle --no-daemon buildExtension
    if($LASTEXITCODE -ne 0){throw 'LE loader extension build failed'}
} finally { Pop-Location }
$destination=Join-Path $ghidraRoot 'Ghidra/Extensions/ghidra-lx-loader/lib'
if(-not(Test-Path $destination)){throw 'Install the LxLoader extension directory before updating its JAR'}
Copy-Item -LiteralPath "$sourceRoot/build/libs/ghidra-lx-loader.jar" -Destination $destination
Write-Output 'Updated project-local LxLoader JAR. Already-running Ghidra processes use it after their next restart.'
