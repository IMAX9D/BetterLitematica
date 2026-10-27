param([Parameter(Mandatory=$true)][string]$Jdk)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
$jdk=$Jdk
# Reuse the exact compile classpath, including the installed BetterLitematica and Fabric jars.
$lines=[IO.File]::ReadAllLines("$root\build\javac.args")
$cp=$lines[6].Trim('"')
$testRoot="$root\build\test-classes"
New-Item -ItemType Directory -Path "$testRoot\META-INF\services" -Force|Out-Null
[IO.File]::WriteAllText("$testRoot\META-INF\services\org.spongepowered.asm.service.IMixinService",'offline.OfflineService')
[IO.File]::WriteAllText("$testRoot\META-INF\services\org.spongepowered.asm.service.IGlobalPropertyService",'offline.OfflineProperties')
$config=Get-Content "$root\resources\backpack_printer.mixins.json" -Raw|ConvertFrom-Json
$config.PSObject.Properties.Remove('plugin')
[IO.File]::WriteAllText("$testRoot\offline.mixins.json",($config|ConvertTo-Json -Depth 8))
$testArgs=@('--release','17','-proc:none','-encoding','UTF-8','-classpath',('"'+"$root/build/classes;$cp".Replace('\','/')+'"'),'-d',('"'+$testRoot.Replace('\','/')+'"'))
$testArgs+=Get-ChildItem "$root\tests" -Recurse -Filter '*.java'|ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'}
[IO.File]::WriteAllLines("$root\build\tests.args",$testArgs,[Text.UTF8Encoding]::new($false))
& "$jdk\bin\javac.exe" "@$root\build\tests.args"
if($LASTEXITCODE -ne 0){throw 'Test compilation failed'}
$runArgs=@('-cp',('"'+"$testRoot;$root/build/classes;$cp".Replace('\','/')+'"'))
[IO.File]::WriteAllLines("$root\build\run.args",$runArgs,[Text.UTF8Encoding]::new($false))
& "$jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$testRoot;$root/build/classes;$cp" BatchChecks
if($LASTEXITCODE -ne 0){throw 'Batch checks failed'}
& "$jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$testRoot;$root/build/classes;$cp" StockChecks
if($LASTEXITCODE -ne 0){throw 'Stock policy checks failed'}
& "$jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$testRoot;$root/build/classes;$cp" StockAdapterChecks
if($LASTEXITCODE -ne 0){throw 'Stock adapter checks failed'}
& "$jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$testRoot;$root/build/classes;$cp" ProtocolChecks
if($LASTEXITCODE -ne 0){throw 'Trash codec checks failed'}
& "$jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$testRoot;$root/build/classes;$cp" CallbackBoundaryChecks "$root\build\classes"
if($LASTEXITCODE -ne 0){throw 'Callback boundary checks failed'}
& "$jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$testRoot;$root/build/classes;$cp" offline.TransformChecks "$root\build\transformed"
if($LASTEXITCODE -ne 0){throw 'Mixin checks failed'}
& "$jdk\bin\java.exe" '-Dfile.encoding=UTF-8' -cp "$testRoot;$root/build/classes;$cp" offline.TransformChecks "$root\build\transformed-combined" combined
if($LASTEXITCODE -ne 0){throw 'Combined Fabric packet-pipeline checks failed'}
