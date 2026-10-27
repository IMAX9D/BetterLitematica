param(
    [Parameter(Mandatory=$true)][string]$Jdk,
    [Parameter(Mandatory=$true)][string]$MinecraftJar,
    [Parameter(Mandatory=$true)][string]$GameLibraries
)
$ErrorActionPreference='Stop'
$root=$PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem
New-Item -ItemType Directory -Path "$root\build\classes","$root\dist" -Force | Out-Null
$cp=@($MinecraftJar)+(Get-ChildItem "$root\libs" -Filter '*.jar').FullName+(Get-ChildItem -LiteralPath $GameLibraries -Recurse -Filter '*.jar' | Where-Object Name -NotLike 'asm-debug-all*').FullName
$cp=$cp -join ';'
$argsFile=@('--release','17','-encoding','UTF-8','-proc:none','-classpath',('"'+$cp.Replace('\','/')+'"'),'-d',('"'+"$root/build/classes".Replace('\','/')+'"'))
$argsFile+=(Get-ChildItem "$root\src" -Recurse -Filter '*.java' | ForEach-Object {'"'+$_.FullName.Replace('\','/')+'"'})
[IO.File]::WriteAllLines("$root\build\javac.args",$argsFile,[Text.UTF8Encoding]::new($false))
& "$Jdk\bin\javac.exe" "@$root\build\javac.args"
if($LASTEXITCODE -ne 0){throw 'Java compilation failed'}
$artifact="$root\dist\backpack-retrieve-1.0.0.jar"
Copy-Item -LiteralPath "$root\libs\backpack-retrieve-original.jar" -Destination $artifact -Force
$zip=[IO.Compression.ZipFile]::Open($artifact,[IO.Compression.ZipArchiveMode]::Update)
try {
    $entry=$zip.GetEntry('fabric.mod.json');$reader=[IO.StreamReader]::new($entry.Open())
    try{$meta=$reader.ReadToEnd()|ConvertFrom-Json}finally{$reader.Dispose()}
    $entry.Delete()
    $meta.depends.minecraft='1.20.1'
    $meta.entrypoints.client=@($meta.entrypoints.client)+@('com.retrievepanel.printer.PrinterRefillClient')
    $meta.mixins=@($meta.mixins)+@('backpack_printer.mixins.json')
    $meta|Add-Member -NotePropertyName 'custom' -NotePropertyValue @{backpackPrinterBuild='2026-10-25-full-stock.5-bl0.4.17'} -Force
    $writer=[IO.StreamWriter]::new($zip.CreateEntry('fabric.mod.json').Open(),[Text.UTF8Encoding]::new($false))
    try{$writer.Write(($meta|ConvertTo-Json -Depth 12))}finally{$writer.Dispose()}
    foreach($sourceRoot in @("$root\build\classes","$root\resources")){
        foreach($file in Get-ChildItem $sourceRoot -Recurse -File){
            $name=[IO.Path]::GetRelativePath($sourceRoot,$file.FullName).Replace('\','/')
            if($zip.GetEntry($name)){throw "Refusing to overwrite original entry: $name"}
            [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip,$file.FullName,$name)|Out-Null
        }
    }
} finally{$zip.Dispose()}
Get-FileHash -LiteralPath $artifact -Algorithm SHA256
