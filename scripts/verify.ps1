# Runs the shared acceptance gates from any working directory. Uses the caller's Java/Android SDK.
# This does not sign release builds, install on devices, or publish anything.
param([switch]$Offline)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
$gradleArguments = @('-p', $projectRoot, '--console=plain',
    ':app:assembleDebug',
    ':app:lintUniversalDebug', ':app:lintModernDebug', ':app:lintEreaderDebug', ':app:lintTvDebug',
    ':app:testUniversalDebugUnitTest', ':app:testModernDebugUnitTest', ':app:testEreaderDebugUnitTest', ':app:testTvDebugUnitTest')
if ($Offline) { $gradleArguments += '--offline' }
& (Join-Path $projectRoot 'gradlew.bat') @gradleArguments
if ($LASTEXITCODE -ne 0) { throw "Android acceptance checks failed (exit $LASTEXITCODE)." }
