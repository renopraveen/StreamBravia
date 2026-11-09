# StreamBravia-TV Auto-Installer
param(
    [string]$DeviceIp = "192.168.0.2:5555",
    [string]$ApkPath = "StreamBravia-TV\bin\StreamBravia-TV.apk"
)

Write-Host "=== StreamBravia-TV Deployment ===" -ForegroundColor Cyan
Write-Host "Target Device: $DeviceIp"
Write-Host "APK: $ApkPath"
Write-Host "Waiting for TV to connect..."

$maxAttempts = 30
$attempt = 0

while ($attempt -lt $maxAttempts) {
    $attempt++
    adb connect $DeviceIp 2>&1 | Out-Null
    $devices = adb devices 2>&1
    if ($devices -match "$([regex]::Escape($DeviceIp))\s+device") {
        Write-Host "Device online! Installing latest build..." -ForegroundColor Green
        $installResult = adb -s $DeviceIp install -r $ApkPath 2>&1
        Write-Host $installResult
        if ($installResult -match "Success") {
            Write-Host "Launching StreamBravia..." -ForegroundColor Green
            adb -s $DeviceIp shell am start -n "com.streambravia.tv/.MainActivity" 2>&1 | Out-Null
            Write-Host "StreamBravia-TV successfully installed and launched!" -ForegroundColor Green
            exit 0
        }
    }
    Start-Sleep -Seconds 2
}

Write-Host "Timed out waiting for $DeviceIp. Please ensure TV is turned on and connected to Wi-Fi." -ForegroundColor Yellow
exit 1
