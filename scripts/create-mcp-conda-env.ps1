param(
    [string]$CondaExe,
    [string]$EnvPrefix,
    [string]$PythonVersion = '3.11'
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\Resolve-StsBridgeConfig.ps1"

# The MCP env defaults to STS_MCP_ENV_PREFIX or CONDA_ENVS_PATH when available.
$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$condaExe = Resolve-CondaExe -CondaExe $CondaExe
$envPrefix = Resolve-McpEnvPrefix -EnvPrefix $EnvPrefix -CondaExe $condaExe
$pythonExe = Join-Path $envPrefix 'python.exe'

if (-not (Test-Path $pythonExe)) {
    & $condaExe create --prefix $envPrefix "python=$PythonVersion" pip -y
    if ($LASTEXITCODE -ne 0) {
        throw "Conda create failed with exit code $LASTEXITCODE"
    }
}

& $pythonExe -m pip install -e $repoRoot
if ($LASTEXITCODE -ne 0) {
    throw "pip install failed with exit code $LASTEXITCODE"
}

Write-Host "Conda environment ready: $envPrefix"
Write-Host "Python executable: $pythonExe"