# Builds the mod jar without Gradle, using only a JDK (17+).
#
#   .\build.ps1                             # uses .ref/dl/server-release.jar if present
#   .\build.ps1 -MindustryJar C:\mindustry\Mindustry.jar
#   .\build.ps1 -Output build\CampaignServerSide.jar
#
# The mod only needs Mindustry on the classpath at compile time; nothing is bundled.

param(
    [string] $MindustryJar = $env:MINDUSTRY_JAR,
    [string] $Output = "build/CampaignServerSide.jar"
)

$ErrorActionPreference = "Stop"
$root = Split-Path -Parent $MyInvocation.MyCommand.Path

if (-not $MindustryJar) {
    $candidates = @(
        (Join-Path $root ".ref/dl/server-release.jar"),
        (Join-Path $root "server-release.jar"),
        (Join-Path $root "Mindustry.jar")
    )
    $MindustryJar = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}

if (-not $MindustryJar -or -not (Test-Path $MindustryJar)) {
    throw "Mindustry jar not found. Pass -MindustryJar <path to server-release.jar or Mindustry.jar>, or set MINDUSTRY_JAR."
}

$javaHome = $env:JAVA_HOME
if ($javaHome -and (Test-Path (Join-Path $javaHome "bin/javac.exe"))) {
    $javac = Join-Path $javaHome "bin/javac.exe"
    $jarTool = Join-Path $javaHome "bin/jar.exe"
} else {
    $javac = "javac"
    $jarTool = "jar"
}

$classes = Join-Path $root "build/classes"
$outputPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path $root $Output }

if (Test-Path $classes) { Remove-Item -Recurse -Force $classes }
New-Item -ItemType Directory -Force -Path $classes | Out-Null
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $outputPath) | Out-Null

$sources = Get-ChildItem -Recurse -File (Join-Path $root "src/main/java") -Filter *.java | ForEach-Object { $_.FullName }
if (-not $sources) { throw "No sources found under src/main/java." }

Write-Host "Compiling $($sources.Count) source file(s) against $MindustryJar ..."
& $javac --release 17 -encoding UTF-8 -Xlint:-options -cp $MindustryJar -d $classes @sources
if ($LASTEXITCODE -ne 0) { throw "Compilation failed." }

Copy-Item (Join-Path $root "mod.hjson") (Join-Path $classes "mod.hjson") -Force
#a leading BOM breaks the hjson key ("\uFEFFname"), which makes the game silently skip the mod
$modJson = Join-Path $classes "mod.hjson"
$modJsonText = [System.IO.File]::ReadAllText($modJson)
if ($modJsonText.Length -gt 0 -and $modJsonText[0] -eq [char]0xFEFF) { $modJsonText = $modJsonText.Substring(1) }
[System.IO.File]::WriteAllText($modJson, $modJsonText, (New-Object System.Text.UTF8Encoding($false)))
if (Test-Path (Join-Path $root "bundles")) {
    Copy-Item -Recurse (Join-Path $root "bundles") (Join-Path $classes "bundles") -Force
}
if (Test-Path (Join-Path $root "assets")) {
    Copy-Item -Recurse (Join-Path $root "assets") (Join-Path $classes "assets") -Force
}

if (Test-Path $outputPath) { Remove-Item -Force $outputPath }
& $jarTool --create --file $outputPath -C $classes .
if ($LASTEXITCODE -ne 0) { throw "Packaging failed." }

$size = [math]::Round((Get-Item $outputPath).Length / 1KB, 1)
Write-Host "Built $outputPath ($size KB)"
