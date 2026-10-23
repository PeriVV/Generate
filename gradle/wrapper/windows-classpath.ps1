param([Parameter(Mandatory = $true)][string]$WrapperJar,
      [Parameter(Mandatory = $true)][string]$Destination)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression
$uri = [System.Uri]::new([System.IO.Path]::GetFullPath($WrapperJar)).AbsoluteUri
$stream = [System.IO.File]::Open($Destination, [System.IO.FileMode]::CreateNew)
try {
    $zip = [System.IO.Compression.ZipArchive]::new($stream, [System.IO.Compression.ZipArchiveMode]::Create, $true)
    try {
        $entry = $zip.CreateEntry('META-INF/MANIFEST.MF')
        $writer = [System.IO.StreamWriter]::new($entry.Open(), [System.Text.UTF8Encoding]::new($false))
        try {
            $writer.NewLine = "`r`n"
            $writer.WriteLine('Manifest-Version: 1.0')
            $line = 'Class-Path: ' + $uri
            while ($line.Length -gt 70) {
                $writer.WriteLine($line.Substring(0, 70))
                $line = ' ' + $line.Substring(70)
            }
            $writer.WriteLine($line)
            $writer.WriteLine()
        } finally { $writer.Dispose() }
    } finally { $zip.Dispose() }
} finally { $stream.Dispose() }
