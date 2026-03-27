param(
    [string]$GameDir,
    [string]$JdkHome,
    [string]$MavenHome,
    [string]$RepoDir,
    [string]$BaseModJar
)

$ErrorActionPreference = "Stop"
. "$PSScriptRoot\Resolve-StsBridgeConfig.ps1"

# Build uses discovered paths by default, but every path can be overridden.
$repoRoot = (Resolve-Path "$PSScriptRoot\..").Path
$gameDir = Resolve-StsGameDir -GameDir $GameDir
$jdkHome = Resolve-JdkHome -JdkHome $JdkHome
$mavenHome = Resolve-MavenHome -MavenHome $MavenHome
$mvnCmd = Resolve-MavenCmd -MavenHome $mavenHome
$repoDir = Resolve-MavenRepo -RepoDir $RepoDir
$baseModJar = Resolve-BaseModJar -GameDir $gameDir -BaseModJar $BaseModJar
$stsJar = Join-Path $gameDir 'desktop-1.0.jar'
$mtsJar = Join-Path $gameDir 'ModTheSpire.jar'

foreach ($required in @($stsJar, $mtsJar, $baseModJar, (Join-Path $jdkHome 'bin\javac.exe'), $mvnCmd)) {
    if (-not (Test-Path $required)) {
        throw "Required file not found: $required"
    }
}

$env:JAVA_HOME = $jdkHome
$env:JDK_HOME = $jdkHome
$env:MAVEN_HOME = $mavenHome
$env:M2_HOME = $mavenHome
$env:Path = "$jdkHome\bin;$mavenHome\bin;" + $env:Path

Push-Location $repoRoot
try {
    & $mvnCmd "-Dmaven.repo.local=$repoDir" "-Dsts.jar=$stsJar" "-Dmts.jar=$mtsJar" "-Dbasemod.jar=$baseModJar" clean package
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}