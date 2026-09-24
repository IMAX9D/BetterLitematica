$ErrorActionPreference = 'Stop'
$Root = Split-Path -Parent $PSScriptRoot
& (Join-Path $PSScriptRoot 'test-core.ps1')
& java -ea -Xmx1G -cp (Join-Path $Root 'build\core-check\classes') dev.betterlitematica.selftest.ImportBenchmark | Tee-Object -FilePath (Join-Path $Root 'build\core-check\benchmark.txt')
if ($LASTEXITCODE -ne 0) { throw "Benchmark failed: $LASTEXITCODE" }
