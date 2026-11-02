# Builds the mod, installs it into a server folder and starts the server.
#
#   .\run-server.ps1 -ServerJar D:\mindustry\server-release.jar
#   .\run-server.ps1 -ServerJar D:\mindustry\server-release.jar -ServerDir D:\mindustry -Commands "campaign-host,groundZero"
#
# The server jar can also be placed at .ref/dl/server-release.jar (used by build.ps1).

param(
    [string] $ServerJar = $env:MINDUSTRY_JAR,
    [string] $ServerDir,
    [string] $Commands
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $ServerJar) {
    $ServerJar = Join-Path $root ".ref/dl/server-release.jar"
}
if (-not (Test-Path $ServerJar)) {
    throw "Server jar not found. Pass -ServerJar <path to server-release.jar>."
}
$ServerJar = (Resolve-Path $ServerJar).Path
if (-not $ServerDir) {
    $ServerDir = Split-Path -Parent $ServerJar
}

& (Join-Path $root "build.ps1") -MindustryJar $ServerJar | Out-Host

$mods = Join-Path $ServerDir "config/mods"
New-Item -ItemType Directory -Force -Path $mods | Out-Null
Copy-Item (Join-Path $root "build/CampaignServerSide.jar") (Join-Path $mods "CampaignServerSide.jar") -Force

$javaHome = $env:JAVA_HOME
$java = if ($javaHome -and (Test-Path (Join-Path $javaHome "bin/java.exe"))) { Join-Path $javaHome "bin/java.exe" } else { "java" }

Write-Host "Starting server in $ServerDir ..."
if ($Commands) {
    & $java -jar $ServerJar $Commands
} else {
    & $java -jar $ServerJar
}
