[CmdletBinding()]
param([ValidateSet('KEEPER.EXE','DEEPER.EXE')][string]$Binary = 'KEEPER.EXE', [switch]$Resume, [switch]$Reanalyze)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$ghidraRoot = Join-Path $projectRoot 'CE-Ghidra-MCP-Pack/runtime/ghidra_12.1.3_PUBLIC'
$jdkRoot = 'C:/Users/kimchi/Desktop/PSPrecomp/CE-Ghidra-MCP-Pack/runtime/jdk-21.0.12.1+1'
if (-not (Test-Path "$jdkRoot/bin/java.exe")) { throw 'Set JDK21 path in tools/decompile-original.ps1' }
$env:JAVA_HOME = $jdkRoot
$env:GHIDRA_HEADLESS_MAXMEM = '6G'
$projectDirectory = Join-Path $projectRoot 'analysis/ghidra'
$destination = Join-Path $projectRoot "analysis/original/$Binary"
New-Item -ItemType Directory -Force -Path $projectDirectory,$destination | Out-Null
$arguments = @($projectDirectory, 'DungeonKeeperOriginal', '-scriptPath', "$projectRoot/tools/ghidra/scripts", '-analysisTimeoutPerFile', '1800', '-preScript', 'PrepareKeeperAnalysis.java', "$projectRoot/Dungeon Keeper/DATA/$Binary", '-postScript', 'ExportKeeper.java', $destination)
if ($Reanalyze) { $arguments += @('30000', 'refresh') }
$arguments += @('-log', "$destination/headless.log", '-scriptlog', "$destination/export.log")
if ($Resume -or $Reanalyze) { $arguments += @('-process', $Binary); if (-not $Reanalyze) { $arguments += '-noanalysis' } }
else { $arguments += @('-import', "$projectRoot/Dungeon Keeper/DATA/$Binary", '-overwrite') }
& "$ghidraRoot/support/analyzeHeadless.bat" @arguments
if ($LASTEXITCODE -ne 0) { throw "Original binary analysis failed: $LASTEXITCODE" }
