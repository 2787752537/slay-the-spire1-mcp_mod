Set-StrictMode -Version Latest

# These helpers keep the repo portable across different machines.
# You can override every detected path with a script parameter or env var.

function Resolve-ExistingPath {
    param(
        [Parameter(Mandatory = $true)][string]$Path,
        [Parameter(Mandatory = $true)][string]$Label
    )

    if (-not (Test-Path $Path)) {
        throw "$Label not found: $Path"
    }

    return (Resolve-Path $Path).Path
}

function Get-SteamRoot {
    foreach ($candidate in @($env:STEAM_PATH)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $registryKeys = @(
        'HKCU:\Software\Valve\Steam',
        'HKLM:\SOFTWARE\WOW6432Node\Valve\Steam'
    )

    foreach ($key in $registryKeys) {
        if (-not (Test-Path $key)) {
            continue
        }

        try {
            $properties = Get-ItemProperty -Path $key
            foreach ($name in @('SteamPath', 'InstallPath')) {
                $property = $properties.PSObject.Properties[$name]
                if (-not $property) {
                    continue
                }
                $value = [string]$property.Value
                if (-not [string]::IsNullOrWhiteSpace($value) -and (Test-Path $value)) {
                    return (Resolve-Path $value).Path
                }
            }
        } catch {
        }
    }

    return $null
}

function Get-SteamLibraryRoots {
    param([string]$SteamRoot)

    $roots = New-Object System.Collections.Generic.List[string]

    if (-not [string]::IsNullOrWhiteSpace($SteamRoot) -and (Test-Path $SteamRoot)) {
        $resolvedRoot = (Resolve-Path $SteamRoot).Path
        if (-not $roots.Contains($resolvedRoot)) {
            [void]$roots.Add($resolvedRoot)
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($SteamRoot)) {
        $libraryVdf = Join-Path $SteamRoot 'steamapps\libraryfolders.vdf'
        if (Test-Path $libraryVdf) {
            foreach ($line in Get-Content -Path $libraryVdf) {
                if ($line -match '"path"\s+"([^"]+)"') {
                    $path = $matches[1] -replace '\\\\', '\'
                    if (Test-Path $path) {
                        $resolvedPath = (Resolve-Path $path).Path
                        if (-not $roots.Contains($resolvedPath)) {
                            [void]$roots.Add($resolvedPath)
                        }
                    }
                }
            }
        }
    }

    return $roots.ToArray()
}

function Resolve-StsGameDir {
    param([string]$GameDir)

    foreach ($candidate in @($GameDir, $env:STS_GAME_DIR)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate)) {
            $resolved = Resolve-ExistingPath -Path $candidate -Label 'Slay the Spire game directory'
            if (Test-Path (Join-Path $resolved 'desktop-1.0.jar')) {
                return $resolved
            }
            throw "desktop-1.0.jar not found under game directory: $resolved"
        }
    }

    $steamRoot = Get-SteamRoot
    foreach ($library in Get-SteamLibraryRoots -SteamRoot $steamRoot) {
        $candidate = Join-Path $library 'steamapps\common\SlayTheSpire'
        if (Test-Path (Join-Path $candidate 'desktop-1.0.jar')) {
            return (Resolve-Path $candidate).Path
        }
    }

    throw 'Could not locate Slay the Spire automatically. Pass -GameDir or set STS_GAME_DIR.'
}

function Resolve-JdkHome {
    param([string]$JdkHome)

    foreach ($candidate in @($JdkHome, $env:STS_JDK_HOME, $env:JAVA_HOME, $env:JDK_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path (Join-Path $candidate 'bin\javac.exe'))) {
            return (Resolve-Path $candidate).Path
        }
    }

    $javac = Get-Command javac.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($javac -and $javac.Source) {
        return (Resolve-Path (Split-Path (Split-Path $javac.Source -Parent) -Parent)).Path
    }

    throw 'Could not locate a JDK with javac.exe. Pass -JdkHome or set JAVA_HOME.'
}

function Resolve-MavenCmd {
    param([string]$MavenHome)

    foreach ($mavenHomeCandidate in @($MavenHome, $env:STS_MAVEN_HOME, $env:MAVEN_HOME, $env:M2_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($mavenHomeCandidate)) {
            $candidate = Join-Path $mavenHomeCandidate 'bin\mvn.cmd'
            if (Test-Path $candidate) {
                return (Resolve-Path $candidate).Path
            }
        }
    }

    foreach ($commandName in @('mvn.cmd', 'mvn')) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command -and $command.Source) {
            return $command.Source
        }
    }

    throw 'Could not locate Maven. Pass -MavenHome or set MAVEN_HOME.'
}

function Resolve-MavenHome {
    param([string]$MavenHome)

    foreach ($mavenHomeCandidate in @($MavenHome, $env:STS_MAVEN_HOME, $env:MAVEN_HOME, $env:M2_HOME)) {
        if (-not [string]::IsNullOrWhiteSpace($mavenHomeCandidate) -and (Test-Path (Join-Path $mavenHomeCandidate 'bin\mvn.cmd'))) {
            return (Resolve-Path $mavenHomeCandidate).Path
        }
    }

    $mvnCmd = Resolve-MavenCmd -MavenHome $MavenHome
    return (Resolve-Path (Split-Path (Split-Path $mvnCmd -Parent) -Parent)).Path
}

function Resolve-MavenRepo {
    param([string]$RepoDir)

    $candidate = $RepoDir
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = $env:STS_MAVEN_REPO
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = $env:MAVEN_REPO_LOCAL
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $settingsPath = Join-Path $HOME '.m2\settings.xml'
        if (Test-Path $settingsPath) {
            try {
                [xml]$settingsXml = Get-Content -Raw -Path $settingsPath
                $configuredRepo = [string]$settingsXml.settings.localRepository
                if (-not [string]::IsNullOrWhiteSpace($configuredRepo)) {
                    $candidate = $configuredRepo
                }
            } catch {
            }
        }
    }
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $candidate = Join-Path $HOME '.m2\repository'
    }

    if (-not (Test-Path $candidate)) {
        New-Item -ItemType Directory -Path $candidate -Force | Out-Null
    }

    return (Resolve-Path $candidate).Path
}

function Resolve-BaseModJar {
    param(
        [Parameter(Mandatory = $true)][string]$GameDir,
        [string]$BaseModJar
    )

    foreach ($candidate in @($BaseModJar, $env:STS_BASEMOD_JAR, (Join-Path $GameDir 'mods\BaseMod.jar'))) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $steamRoot = Get-SteamRoot
    foreach ($library in Get-SteamLibraryRoots -SteamRoot $steamRoot) {
        $workshopRoot = Join-Path $library 'steamapps\workshop\content\646570'
        if (-not (Test-Path $workshopRoot)) {
            continue
        }

        $match = Get-ChildItem -Path $workshopRoot -Recurse -Filter 'BaseMod.jar' -File -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($match) {
            return $match.FullName
        }
    }

    throw 'Could not locate BaseMod.jar. Pass -BaseModJar or set STS_BASEMOD_JAR.'
}

function Resolve-CondaExe {
    param([string]$CondaExe)

    foreach ($candidate in @($CondaExe, $env:CONDA_EXE)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    foreach ($commandName in @('conda.exe', 'conda.bat', 'conda')) {
        $command = Get-Command $commandName -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($command -and $command.Source) {
            return $command.Source
        }
    }

    throw 'Could not locate conda. Pass -CondaExe or set CONDA_EXE.'
}

function Resolve-McpEnvPrefix {
    param(
        [string]$EnvPrefix,
        [string]$CondaExe
    )

    foreach ($candidate in @($EnvPrefix, $env:STS_MCP_ENV_PREFIX)) {
        if (-not [string]::IsNullOrWhiteSpace($candidate)) {
            return $candidate
        }
    }

    if (-not [string]::IsNullOrWhiteSpace($env:CONDA_ENVS_PATH)) {
        return (Join-Path $env:CONDA_ENVS_PATH 'sts-game-mcp')
    }

    if (-not [string]::IsNullOrWhiteSpace($CondaExe)) {
        $condaRoot = Split-Path (Split-Path $CondaExe -Parent) -Parent
        if (-not [string]::IsNullOrWhiteSpace($condaRoot)) {
            return (Join-Path $condaRoot 'envs\sts-game-mcp')
        }
    }

    return (Join-Path $HOME '.conda\envs\sts-game-mcp')
}

function Resolve-McpPythonExe {
    param(
        [string]$PythonExe,
        [string]$EnvPrefix
    )

    $candidates = @($PythonExe, $env:STS_MCP_PYTHON)
    if (-not [string]::IsNullOrWhiteSpace($EnvPrefix)) {
        $candidates += (Join-Path $EnvPrefix 'python.exe')
    }

    foreach ($candidate in $candidates) {
        if (-not [string]::IsNullOrWhiteSpace($candidate) -and (Test-Path $candidate)) {
            return (Resolve-Path $candidate).Path
        }
    }

    $command = Get-Command python.exe -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($command -and $command.Source) {
        return $command.Source
    }

    throw 'Could not locate python.exe for the MCP server. Pass -PythonExe or -EnvPrefix.'
}