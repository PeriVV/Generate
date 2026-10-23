param([string]$ProjectRoot = (Get-Location).Path)
$ErrorActionPreference = 'Stop'
$fixture = Join-Path $ProjectRoot 'tmp/oss/commons-codec'
if (-not (Test-Path "$fixture/.git")) {
    New-Item -ItemType Directory -Path (Split-Path $fixture) -Force | Out-Null
    git -c core.autocrlf=false clone --depth 1 --branch rel/commons-codec-1.16.1 https://github.com/apache/commons-codec.git $fixture
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
$classes = Join-Path $fixture 'target/classes'
New-Item -ItemType Directory -Path $classes -Force | Out-Null
$arguments = @('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-g', '-d', $classes)
$arguments += @(Get-ChildItem "$fixture/src/main/java" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
$argumentFile = Join-Path $env:RUNNER_TEMP 'unitmind-codec-javac.args'
$quoted = @($arguments | ForEach-Object { '"' + $_.Replace('\', '\\').Replace('"', '\"') + '"' })
[System.IO.File]::WriteAllLines($argumentFile, $quoted, [System.Text.UTF8Encoding]::new($false))
& "$env:JAVA_HOME/bin/javac.exe" '-J-Dfile.encoding=UTF-8' "@$argumentFile"
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
[System.IO.File]::WriteAllText((Join-Path $fixture 'target/unitmind.classpath'), '', [System.Text.UTF8Encoding]::new($false))
"UNITMIND_CODEC_COMMIT=$(git -C $fixture rev-parse HEAD)" >> $env:GITHUB_ENV
