param(
    [string]$PythonExe,
    [string]$EnvPrefix,
    [string]$RuntimeDir
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\Resolve-StsBridgeConfig.ps1"

$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
if ([string]::IsNullOrWhiteSpace($RuntimeDir)) {
    $RuntimeDir = $env:STS_MCP_RUNTIME_DIR
}
if ([string]::IsNullOrWhiteSpace($RuntimeDir)) {
    $RuntimeDir = Join-Path $repoRoot 'runtime'
}

if ([string]::IsNullOrWhiteSpace($EnvPrefix)) {
    $resolvedConda = $null
    try {
        $resolvedConda = Resolve-CondaExe -CondaExe $null
    } catch {
        $resolvedConda = $null
    }
    $EnvPrefix = Resolve-McpEnvPrefix -EnvPrefix $null -CondaExe $resolvedConda
}

$pythonExe = Resolve-McpPythonExe -PythonExe $PythonExe -EnvPrefix $EnvPrefix
$env:STS_MCP_RUNTIME_DIR = $RuntimeDir
& $pythonExe -m sts_game_mcp --runtime-dir $RuntimeDir