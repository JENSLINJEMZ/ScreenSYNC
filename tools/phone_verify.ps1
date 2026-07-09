# Run the LEDDMX protocol verification through nRF Connect on an ADB-connected
# Android phone. Requires: USB debugging authorized, nRF Connect for Mobile
# installed (package no.nordicsemi.android.mcp), LED LAMP app force-closed.
param(
    [string]$XmlPath = "$PSScriptRoot\phone_verify.xml"
)

$pkg = "no.nordicsemi.android.mcp"
$xmlName = [IO.Path]::GetFileName($XmlPath)

$state = adb get-state 2>$null
if ($state -ne "device") {
    Write-Error "No ADB device connected (state: $state). Plug the phone in and authorize USB debugging."
    exit 1
}
if (-not (adb shell pm list packages $pkg | Select-String $pkg)) {
    Write-Error "nRF Connect for Mobile is not installed on the phone."
    exit 1
}

# Preferred location per Nordic docs; falls back to Download on scoped-storage
# Android versions where adb cannot write into Android/data.
$dest = "/sdcard/Android/data/$pkg/files/Test/$xmlName"
adb push $XmlPath $dest 2>$null
if ($LASTEXITCODE -ne 0) {
    $dest = "/sdcard/Download/$xmlName"
    adb push $XmlPath $dest
    if ($LASTEXITCODE -ne 0) { Write-Error "adb push failed"; exit 1 }
}

Write-Host "Starting test (watch the strip: it should go RED -> GREEN -> BLUE, 4s apart)..."
adb shell am start-foreground-service -a no.nordicsemi.android.action.START_TEST `
    -e no.nordicsemi.android.test.extra.EXTRA_FILE_PATH "$dest"

Start-Sleep -Seconds 30

$resultRemote = $dest -replace '\.xml$', '_result.txt'
$resultLocal = "$PSScriptRoot\phone_verify_result.txt"
adb pull $resultRemote $resultLocal 2>$null
if ($LASTEXITCODE -eq 0) {
    Write-Host "`n--- test result ---"
    Get-Content $resultLocal
} else {
    Write-Warning "Result file not found yet ($resultRemote). Check the nRF Connect notification on the phone."
}
