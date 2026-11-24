param(
    [string] $Libs = (Join-Path $PSScriptRoot '../build/libs')
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$project = Split-Path -Parent $PSScriptRoot
$versionLine = Select-String -Path (Join-Path $project 'gradle.properties') -Pattern '^mod_version=(.+)$'
$version = $versionLine.Matches[0].Groups[1].Value
$base = "speakersinheads-$version"
$runtime = Get-Item -LiteralPath (Join-Path $Libs "$base.jar")
$sources = Get-Item -LiteralPath (Join-Path $Libs "$base-sources.jar")

$archive = [IO.Compression.ZipFile]::OpenRead($runtime.FullName)
try {
    $entry = $archive.GetEntry('fabric.mod.json')
    if ($null -eq $entry) { throw 'Missing fabric.mod.json' }
    $reader = [IO.StreamReader]::new($entry.Open())
    try { $metadata = $reader.ReadToEnd() | ConvertFrom-Json } finally { $reader.Dispose() }
    if ($metadata.id -ne 'speakersinheads' -or $metadata.version -ne $version -or
        $metadata.environment -ne 'client' -or $metadata.depends.minecraft -ne '~26.3' -or
        $metadata.depends.java -ne '>=25') {
        throw 'Unexpected Fabric metadata'
    }
    $nested = @($archive.Entries | Where-Object FullName -Like 'META-INF/jars/*.jar')
    if ($nested.Count -ne 3 -or @($metadata.jars).Count -ne 3) {
        throw 'Unexpected number of embedded jars'
    }
    foreach ($name in @('mp3spi', 'jlayer-decoder', 'tritonus-share')) {
        if (@($nested | Where-Object Name -Like "$name-*.jar").Count -ne 1) {
            throw "Expected exactly one embedded $name jar"
        }
    }
    foreach ($resource in @('META-INF/THIRD_PARTY_NOTICES.md', 'META-INF/licenses/LGPL-2.1.txt')) {
        if ($null -eq $archive.GetEntry($resource)) { throw "Missing $resource" }
    }
} finally {
    $archive.Dispose()
}

$archive = [IO.Compression.ZipFile]::OpenRead($sources.FullName)
try {
    if (@($archive.Entries | Where-Object Name -EQ 'SpeakersInHeadsClient.java').Count -ne 1) {
        throw 'Client sources are missing'
    }
} finally {
    $archive.Dispose()
}

foreach ($artifact in @($runtime, $sources)) {
    $hash = (Get-FileHash -LiteralPath $artifact.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    "$hash  $($artifact.Name)"
}
