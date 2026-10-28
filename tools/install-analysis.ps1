[CmdletBinding()]
param([string]$Jdk21 = 'C:/Users/kimchi/Desktop/PSPrecomp/CE-Ghidra-MCP-Pack/runtime/jdk-21.0.12.1+1', [int]$Port=8091)
$ErrorActionPreference='Stop'
$projectRoot=Split-Path -Parent $PSScriptRoot
$pack=Join-Path $projectRoot 'CE-Ghidra-MCP-Pack'
$python=Join-Path $pack 'ghidra_brawl/.venv/Scripts/python.exe'
if(-not(Test-Path $python)) {
    & py -3.13 -m venv "$pack/ghidra_brawl/.venv"
    if($LASTEXITCODE -ne 0){throw 'Ghidra environment creation failed'}
    & $python -m pip install --disable-pip-version-check "$pack/ghidra_brawl" tomlkit==0.13.3
    if($LASTEXITCODE -ne 0){throw 'Ghidra bridge installation failed'}
}
$settings=@{
    pack_root=$pack; python_executable=$python;
    ghidra_directory="$pack/runtime/ghidra_12.1.3_PUBLIC";
    java_executable="$Jdk21/bin/java.exe"; ghidra_port=$Port;
    ghidra_url="http://127.0.0.1:$Port";
    analysis_directory="$projectRoot/analysis";
    ce_executable=$null;
}
foreach($path in @($settings.java_executable,"$($settings.ghidra_directory)/ghidraRun.bat")) {
    if(-not(Test-Path -LiteralPath $path)){throw "Missing prerequisite: $path"}
}
New-Item -ItemType Directory -Force -Path "$pack/config" | Out-Null
$utf8=New-Object System.Text.UTF8Encoding($false)
[IO.File]::WriteAllText("$pack/config/install.local.json",($settings|ConvertTo-Json),$utf8)
$missing=@('runtime/session.py','runtime/scans.py','runtime/watches.py','runtime/patches.py','runtime/recovery.py','analysis/validation.py','analysis/signatures.py','analysis/address_mapping.py','analysis/structures.py') | Where-Object {-not(Test-Path "$pack/ce_modding/src/modworkbench/$_")}
$report=@{ghidra_python=$python;ghidra_version='12.1.3';ghidra_bridge_version='7.0.0';ghidra_port=$Port;ce_available=($missing.Count -eq 0);ce_missing_modules=$missing;note='CE managed workbench is not registered while packaged source modules are missing. Ghidra is independently functional.'}
[IO.File]::WriteAllText("$pack/config/installation-status.json",($report|ConvertTo-Json -Depth 5),$utf8)
& $python "$pack/scripts/ghidra_runtime.py"
if($LASTEXITCODE -ne 0){throw 'Ghidra MCP backend failed to start'}
Write-Output 'Project-local Ghidra MCP installation ready. CE limitation recorded in config/installation-status.json.'
