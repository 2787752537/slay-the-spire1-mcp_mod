$ErrorActionPreference = "Stop"

$jdkHome = "D:\study software\JDK"
$mavenHome = "D:\study software\apache-maven-3.9.13"
$repoDir = "D:\study software\maven-repository"
$gameDir = "F:\game\steam\steamapps\common\SlayTheSpire"
$stsJar = Join-Path $gameDir "desktop-1.0.jar"
$mtsJar = Join-Path $gameDir "ModTheSpire.jar"
$baseModJar = Join-Path $gameDir "mods\BaseMod.jar"
$mvnCmd = Join-Path $mavenHome "bin\mvn.cmd"

foreach ($required in @("$jdkHome\bin\javac.exe", $mvnCmd, $stsJar, $mtsJar, $baseModJar)) {
    if (-not (Test-Path $required)) {
        throw "Required file not found: $required"
    }
}

if (-not (Test-Path $repoDir)) {
    New-Item -ItemType Directory -Path $repoDir -Force | Out-Null
}

$env:JAVA_HOME = $jdkHome
$env:JDK_HOME = $jdkHome
$env:MAVEN_HOME = $mavenHome
$env:M2_HOME = $mavenHome
$env:Path = "$jdkHome\bin;$mavenHome\bin;" + $env:Path

Push-Location $PSScriptRoot\..
try {
    cmd /c "call ""$mvnCmd"" -Dmaven.repo.local=""$repoDir"" clean package"
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed with exit code $LASTEXITCODE"
    }
} finally {
    Pop-Location
}