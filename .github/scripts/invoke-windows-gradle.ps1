param([Parameter(Mandatory = $true)][string[]]$GradleArguments)
$ErrorActionPreference = 'Stop'
$options = @(
    "-PunitmindTestJava8Home=$env:UNITMIND_JAVA8_HOME",
    "-PunitmindTestJava11Home=$env:UNITMIND_JAVA11_HOME",
    "-PunitmindTestJava21Home=$env:UNITMIND_JAVA21_HOME",
    "-PunitmindTestJava25Home=$env:UNITMIND_JAVA25_HOME",
    "-PunitmindVerifierJavaHome=$env:UNITMIND_JAVA25_HOME",
    "-PunitmindBuildDirectory=$env:UNITMIND_NATIVE_BUILD",
    '-PunitmindSetupFixturesOnline=true', '--no-daemon', '--console=plain'
)
& ./gradlew.bat @GradleArguments @options
exit $LASTEXITCODE
