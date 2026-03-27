param(
    [string]$GameDir
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
    & $javaExe -jar $mtsJar
} finally {
    Pop-Location
}