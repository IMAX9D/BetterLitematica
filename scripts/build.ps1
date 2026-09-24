param(
    [Parameter(ValueFromRemainingArguments=$true)]
    [string[]]$Tasks = @('coreCheck', ':fabric-1.20.1:build')
)
$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Version = '8.6'
$ExpectedSha256 = '9631d53cf3e74bfa726893aee1f8994fee4e060c401335946dba2156f440f24c'
$Tools = Join-Path $Root '.tools'
$Gradle = Join-Path $Tools "gradle-$Version\bin\gradle.bat"
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'A JDK is required. Set JAVA_HOME and PATH; JDK 17 is recommended.' }
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
    if ((Get-FileHash -LiteralPath $Zip -Algorithm SHA256).Hash.ToLowerInvariant() -ne $ExpectedSha256) { throw "Checksum mismatch: $Zip. Replace it with the official Gradle 8.6 binary distribution." }
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
    & $Gradle --console=plain --no-daemon @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed with exit code $LASTEXITCODE. See the first compilation/dependency error above." }
} finally { Pop-Location }
