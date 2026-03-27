param(
    [string]$GameDir
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\Resolve-StsBridgeConfig.ps1"

$projectRoot = (Resolve-Path "$PSScriptRoot\..").Path
$jarPath = Join-Path $projectRoot 'target\StsModStarter.jar'
$gameDir = Resolve-StsGameDir -GameDir $GameDir
$modsDir = Join-Path $gameDir 'mods'

if (-not (Test-Path $jarPath)) {
    throw "Build output not found: $jarPath. Run scripts\\build.ps1 first."
}

if (-not (Test-Path $modsDir)) {
    New-Item -ItemType Directory -Path $modsDir -Force | Out-Null
}

Copy-Item $jarPath (Join-Path $modsDir 'StsModStarter.jar') -Force
Write-Output "Installed to $(Join-Path $modsDir 'StsModStarter.jar')"