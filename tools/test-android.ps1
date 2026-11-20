param(
    [string]$SdkPath = $env:ANDROID_HOME,
    [string]$Serial = 'emulator-5580',
    [switch]$Reboot,
    [switch]$SkipInstall
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot
if (-not $SdkPath) {
    $sdkProperty = (Select-String -LiteralPath (Join-Path $projectRoot 'local.properties') -Pattern '^sdk.dir=').Line
    if (-not $sdkProperty) { throw 'Set -SdkPath or ANDROID_HOME' }
    $SdkPath = $sdkProperty.Substring(8).Replace('\:', ':').Replace('\\', '\')
}
$adbExecutable = Join-Path $SdkPath 'platform-tools/adb.exe'
if ($Serial -notmatch '^emulator-\d+$') { throw 'This script only runs on a disposable emulator' }
$avdName = & $adbExecutable -s $Serial emu avd name
if ($LASTEXITCODE -ne 0 -or $avdName -notcontains 'MusicTest') { throw 'Expected the isolated MusicTest emulator' }
$outputDirectory = Join-Path $projectRoot 'tmp/android-checks'
New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null

function Invoke-TestAdb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)
    & $adbExecutable -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) { throw "ADB failed: $($Arguments -join ' ')" }
}
function Invoke-InstrumentedCheck {
    param([string]$Class, [string]$LogName, [switch]$AfterReboot)
    $arguments = @('shell', 'am', 'instrument', '-w', '-e', 'class', $Class)
    if ($AfterReboot) { $arguments += @('-e', 'afterReboot', 'true') }
    $arguments += 'com.example.music.test/androidx.test.runner.AndroidJUnitRunner'
    $output = & $adbExecutable -s $Serial @arguments
    $output | Tee-Object -FilePath (Join-Path $outputDirectory $LogName)
    if ($LASTEXITCODE -ne 0 -or ($output -join "`n") -notmatch 'OK \(\d+ tests?\)') {
        throw "Android check failed; see $LogName"
    }
}

if (-not $SkipInstall) {
    Invoke-TestAdb install -r (Join-Path $projectRoot 'app/build/outputs/apk/debug/app-debug.apk')
    Invoke-TestAdb install -r (Join-Path $projectRoot 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk')
}
Invoke-TestAdb shell pm grant com.example.music android.permission.POST_NOTIFICATIONS
Invoke-TestAdb shell cmd package compile -m speed -f com.example.music
Invoke-TestAdb shell cmd package compile -m speed -f com.example.music.test
Invoke-InstrumentedCheck -Class 'com.example.music.ReliabilityTest,com.example.music.PlaybackAndDownloadTest,com.example.music.PlaybackRegressionTest,com.example.music.ArtworkUiRegressionTest' -LogName 'core.log'
if ($Reboot) {
    Invoke-InstrumentedCheck -Class 'com.example.music.RebootPersistenceTest#aPrepare' -LogName 'before-reboot.log'
    Invoke-TestAdb reboot
    Invoke-TestAdb wait-for-device
    $booted = $false
    for ($attempt = 0; $attempt -lt 60; $attempt++) {
        if ((& $adbExecutable -s $Serial shell getprop sys.boot_completed) -eq '1') { $booted = $true; break }
        Start-Sleep -Seconds 2
    }
    if (-not $booted) { throw 'Test emulator did not boot in time' }
    Invoke-TestAdb shell cmd package compile -m speed -f com.example.music
    Invoke-TestAdb shell cmd package compile -m speed -f com.example.music.test
    Invoke-InstrumentedCheck -Class 'com.example.music.RebootPersistenceTest#bVerify' -AfterReboot -LogName 'after-reboot.log'
}
& $adbExecutable -s $Serial shell test -f /sdcard/Android/data/com.example.music/files/playback-test.png
if ($LASTEXITCODE -eq 0) {
    Invoke-TestAdb pull /sdcard/Android/data/com.example.music/files/playback-test.png (Join-Path $outputDirectory 'playback-test.png')
}
