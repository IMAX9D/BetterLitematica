# JDK 25+: ./scripts/build.ps1 :fabric-1.21.11:build
# Build every release in targets.json: ./scripts/build.ps1 -AllVersions
# Install the matching JAR from fabric-1.20.1/build/libs or versions/<game>/build/libs.
param(
    [switch]$AllVersions,
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$Tasks = @(':fabric-1.20.1:build')
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Version = '9.6.0'
$ExpectedSha256 = 'bbaeb2fef8710818cf0e261201dab964c572f92b942812df0c3620d62a529a01'
$Tools = Join-Path $Root '.tools'
$Gradle = Join-Path $Tools "gradle-$Version\bin\gradle.bat"
$Java = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/java.exe' } else { 'java' }
$Javac = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME 'bin/javac.exe' } else { 'javac' }
if (-not (Get-Command $Java -ErrorAction SilentlyContinue) -or -not (Get-Command $Javac -ErrorAction SilentlyContinue)) { throw 'JDK 25 or later is required. Set JAVA_HOME to a complete JDK.' }
$CompilerVersion = (& $Javac -version 2>&1 | Out-String).Trim()
if ($CompilerVersion -notmatch '^javac (\d+)' -or [int]$Matches[1] -lt 25) { throw "JDK 25 or later is required; found $CompilerVersion." }
$RuntimeVersion = (& $Java -version 2>&1 | Out-String).Trim()
if ($RuntimeVersion -notmatch '(?:openjdk|java) version "(\d+)' -or [int]$Matches[1] -lt 25) { throw "Gradle requires JDK 25 or later; found $RuntimeVersion." }
if (-not (Test-Path -LiteralPath $Gradle -PathType Leaf)) {
    New-Item -ItemType Directory -Force $Tools | Out-Null
    $Zip = Join-Path $Tools "gradle-$Version-bin.zip"
    if (-not (Test-Path -LiteralPath $Zip -PathType Leaf)) {
        $Temp = "$Zip.partial"
        [Net.ServicePointManager]::SecurityProtocol = [Net.ServicePointManager]::SecurityProtocol -bor [Net.SecurityProtocolType]::Tls12
        Write-Host "Downloading official Gradle $Version; the pinned SHA-256 will be verified."
        try {
            Invoke-WebRequest -UseBasicParsing -Uri "https://services.gradle.org/distributions/gradle-$Version-bin.zip" -OutFile $Temp -TimeoutSec 300
            if ((Get-FileHash -LiteralPath $Temp -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedSha256) { throw 'Gradle checksum mismatch. Refusing to execute the download.' }
            Move-Item -LiteralPath $Temp -Destination $Zip
        } finally { if (Test-Path -LiteralPath $Temp) { Remove-Item -LiteralPath $Temp -Force } }
    }
    if ((Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedSha256) { throw "Checksum mismatch: $Zip. Replace it with the official Gradle $Version binary distribution." }
    $Stage = Join-Path $Tools ('unpack-' + [Guid]::NewGuid().ToString('N'))
    try {
        Expand-Archive -LiteralPath $Zip -DestinationPath $Stage
        $Destination = Join-Path $Tools "gradle-$Version"
        if (Test-Path -LiteralPath $Destination) { throw "Incomplete Gradle directory already exists: $Destination. Inspect it before retrying." }
        Move-Item -LiteralPath (Join-Path $Stage "gradle-$Version") -Destination $Destination
    } finally { if (Test-Path -LiteralPath $Stage) { Remove-Item -LiteralPath $Stage -Recurse -Force } }
}
Push-Location $Root
try {
    if ($AllVersions) {
        # Keep each game toolchain isolated so building every target has bounded memory use.
        $Targets = (Get-Content -LiteralPath (Join-Path $Root 'targets.json') -Raw | ConvertFrom-Json).targets
        foreach ($Target in $Targets) {
            & $Gradle --console=plain --no-daemon --max-workers=2 ":fabric-$($Target.minecraft):build"
            if ($LASTEXITCODE -ne 0) { throw "Build failed for Minecraft $($Target.minecraft) (exit $LASTEXITCODE)." }
        }
    } else {
        & $Gradle --console=plain --no-daemon --max-workers=2 @Tasks
        if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE. See the first compilation/dependency error above." }
    }
} finally { Pop-Location }
