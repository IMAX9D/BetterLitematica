$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
$Out = Join-Path $Root 'build\core-check'
New-Item -ItemType Directory -Force (Join-Path $Out 'classes') | Out-Null
$Sources = @('core', 'blueprint-io', 'runtime', 'selftest') | ForEach-Object {
    Get-ChildItem -Path (Join-Path $Root "$_\src\main\java") -Recurse -Filter '*.java' | ForEach-Object {
        '"' + $_.FullName.Replace('\', '/') + '"'
    }
}
$ArgFile = Join-Path $Out 'sources.txt'
[System.IO.File]::WriteAllLines($ArgFile, [string[]]$Sources, [System.Text.UTF8Encoding]::new($false))
& javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d (Join-Path $Out 'classes') "@$ArgFile"
if ($LASTEXITCODE -ne 0) { throw "Core compilation failed: $LASTEXITCODE" }
& java -ea -Xmx1G -cp (Join-Path $Out 'classes') dev.betterlitematica.selftest.CoreTests | Tee-Object -FilePath (Join-Path $Out 'results.txt')
if ($LASTEXITCODE -ne 0) { throw "Core tests failed: $LASTEXITCODE" }
