$ErrorActionPreference = "Stop"

$projectRoot = Resolve-Path "$PSScriptRoot\.."
$jarPath = Join-Path $projectRoot "target\StsModStarter.jar"
$gameDir = "F:\game\steam\steamapps\common\SlayTheSpire"
$modsDir = Join-Path $gameDir "mods"

if (-not (Test-Path $jarPath)) {
    throw "Build output not found: $jarPath. Run scripts\\build.ps1 first."
}

if (-not (Test-Path $modsDir)) {
    New-Item -ItemType Directory -Path $modsDir | Out-Null
}

Copy-Item $jarPath (Join-Path $modsDir "StsModStarter.jar") -Force
Write-Output "Installed to $(Join-Path $modsDir 'StsModStarter.jar')"
