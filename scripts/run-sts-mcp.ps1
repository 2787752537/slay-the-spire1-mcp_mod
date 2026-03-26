param(
    [string]$EnvPrefix = "D:\Anaconda\Anaconda\envs\sts-game-mcp",
    [string]$RuntimeDir
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$pythonExe = Join-Path $EnvPrefix "python.exe"

if (-not (Test-Path $pythonExe)) {
    throw "Python not found at $pythonExe. Run scripts\\create-mcp-conda-env.ps1 first."
}

if ([string]::IsNullOrWhiteSpace($RuntimeDir)) {
    $RuntimeDir = $env:STS_MCP_RUNTIME_DIR
}

if ([string]::IsNullOrWhiteSpace($RuntimeDir)) {
    $RuntimeDir = Join-Path $repoRoot "runtime"
}

$env:STS_MCP_RUNTIME_DIR = $RuntimeDir
& $pythonExe -m sts_game_mcp --runtime-dir $RuntimeDir
