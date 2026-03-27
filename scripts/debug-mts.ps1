param(
    [string]$GameDir,
    [int]$DebugPort = 5005
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\Resolve-StsBridgeConfig.ps1"

$gameDir = Resolve-StsGameDir -GameDir $GameDir
$javaExe = Join-Path $gameDir 'jre\bin\java.exe'
$mtsJar = Join-Path $gameDir 'ModTheSpire.jar'

if (-not (Test-Path $javaExe)) {
    throw "Bundled game Java not found at $javaExe"
}

if (-not (Test-Path $mtsJar)) {
    throw "ModTheSpire.jar not found at $mtsJar"
}

Push-Location $gameDir
try {
    & $javaExe "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=$DebugPort" -jar $mtsJar
} finally {
    Pop-Location
}