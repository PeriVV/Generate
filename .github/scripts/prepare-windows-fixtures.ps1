param([string]$ProjectRoot = (Get-Location).Path)
$ErrorActionPreference = 'Stop'
$projects = @(
    @{ Name = 'commons-codec'; Tag = 'rel/commons-codec-1.16.1'; Variable = 'UNITMIND_CODEC_COMMIT' },
    @{ Name = 'commons-io'; Tag = 'rel/commons-io-2.16.1'; Variable = 'UNITMIND_IO_COMMIT' },
    @{ Name = 'commons-lang'; Tag = 'rel/commons-lang-3.14.0'; Variable = 'UNITMIND_LANG_COMMIT' }
)
foreach ($project in $projects) {
    $fixture = Join-Path $ProjectRoot "tmp/oss/$($project.Name)"
    if (-not (Test-Path "$fixture/.git")) {
        New-Item -ItemType Directory -Path (Split-Path $fixture) -Force | Out-Null
        git -c core.autocrlf=false clone --depth 1 --branch $project.Tag "https://github.com/apache/$($project.Name).git" $fixture
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
    $commit = git -C $fixture rev-parse HEAD
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $tagCommit = git -C $fixture rev-parse "$($project.Tag)^{commit}"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    if ($commit -ne $tagCommit) { throw "Unexpected fixture revision: $($project.Name)" }
    $classes = Join-Path $fixture 'target/classes'
    New-Item -ItemType Directory -Path $classes -Force | Out-Null
    $arguments = @('-encoding', 'UTF-8', '-source', '8', '-target', '8', '-g', '-d', $classes)
    $arguments += @(Get-ChildItem "$fixture/src/main/java" -Recurse -Filter '*.java' | ForEach-Object { $_.FullName })
    $argumentFile = Join-Path $env:RUNNER_TEMP "unitmind-$($project.Name)-javac.args"
    $quoted = @($arguments | ForEach-Object { '"' + $_.Replace('\', '\\').Replace('"', '\"') + '"' })
    [System.IO.File]::WriteAllLines($argumentFile, $quoted, [System.Text.UTF8Encoding]::new($false))
    & "$env:JAVA_HOME/bin/javac.exe" '-J-Dfile.encoding=UTF-8' "@$argumentFile"
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    [System.IO.File]::WriteAllText((Join-Path $fixture 'target/unitmind.classpath'), '', [System.Text.UTF8Encoding]::new($false))
    "$($project.Variable)=$commit" >> $env:GITHUB_ENV
}
