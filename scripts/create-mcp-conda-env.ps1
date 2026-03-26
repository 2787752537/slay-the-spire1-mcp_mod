param(
    [string]$CondaExe = "D:\Anaconda\Anaconda\Scripts\conda.exe",
    [string]$EnvPrefix = "D:\Anaconda\Anaconda\envs\sts-game-mcp"
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$pythonExe = Join-Path $EnvPrefix "python.exe"

if (-not (Test-Path $CondaExe)) {
    throw "Conda not found at $CondaExe"
}

if (-not (Test-Path $pythonExe)) {
    & $CondaExe create --prefix $EnvPrefix python=3.11 pip -y
}

& $pythonExe -m pip install -e $repoRoot

Write-Host "Conda environment ready: $EnvPrefix"
Write-Host "Python executable: $pythonExe"
