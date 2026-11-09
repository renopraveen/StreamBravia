$ErrorActionPreference = "Stop"

$projectDir = "c:\Users\renop\Downloads\YTS\StreamBravia-TV"
if (Test-Path "C:\Users\renop\Downloads\freetube\apk\android-sdk") {
    $sdk = "C:\Users\renop\Downloads\freetube\apk\android-sdk"
} elseif (Test-Path "C:\Users\renop\Downloads\renotube\android-sdk") {
    $sdk = "C:\Users\renop\Downloads\renotube\android-sdk"
} else {
    throw "Android SDK not found"
}
$bt = "$sdk\build-tools\34.0.0"
$jar = "$sdk\platforms\android-34\android.jar"
$ks = "C:\Users\renop\.android\debug.keystore"

Set-Location $projectDir

Write-Output "Step 1: Generating R.java with aapt..."
& "$bt\aapt.exe" package -f -m -J gen -M AndroidManifest.xml -S res -I $jar
if ($LASTEXITCODE -ne 0) { throw "aapt R.java failed with code $LASTEXITCODE" }
Write-Output "R.java generated."

Write-Output "Step 2: Compiling Java..."
$files = Get-ChildItem -Path src, gen -Recurse -Filter *.java | Select-Object -ExpandProperty FullName
& javac -encoding UTF-8 -d obj -cp "$jar;gen;libs\*" $files
if ($LASTEXITCODE -ne 0) { throw "javac failed with code $LASTEXITCODE" }
Write-Output "Java compilation succeeded."

Write-Output "Step 3: D8 dexing..."
& jar cf bin\app_classes.jar -C obj .
$libJars = Get-ChildItem -Path libs -Filter *.jar | Select-Object -ExpandProperty FullName
& "$bt\d8.bat" --min-api 26 --output bin bin\app_classes.jar $libJars
if ($LASTEXITCODE -ne 0) { throw "d8 failed with code $LASTEXITCODE" }
Write-Output "D8 dexing succeeded."

Write-Output "Step 4: Packaging unsigned APK with assets..."
& "$bt\aapt.exe" package -f -M AndroidManifest.xml -S res -A assets -I $jar -F bin\app.unsigned.apk
if ($LASTEXITCODE -ne 0) { throw "aapt package failed with code $LASTEXITCODE" }

Write-Output "Step 5: Adding classes.dex and native libraries to APK..."
Set-Location "$projectDir"
# Add native .so libraries for ARM and ARM64
python -c "import zipfile, os; apk = zipfile.ZipFile('bin/app.unsigned.apk', 'a', compression=zipfile.ZIP_DEFLATED); [apk.write(os.path.join(root, f), os.path.relpath(os.path.join(root, f), '.')) for root, _, files in os.walk('lib') for f in files if f.endswith('.so')]; apk.close(); print('Native libraries added to APK')"

Set-Location "$projectDir\bin"
$dexFiles = Get-ChildItem -Filter "classes*.dex" | Select-Object -ExpandProperty Name
foreach ($d in $dexFiles) {
    & "$bt\aapt.exe" add app.unsigned.apk $d
}
Set-Location $projectDir

Write-Output "Step 6: Zipalign APK..."
if (Test-Path "$projectDir\bin\StreamBravia-TV.apk") {
    Remove-Item "$projectDir\bin\StreamBravia-TV.apk" -Force
}
& "$bt\zipalign.exe" -f -p 4 "$projectDir\bin\app.unsigned.apk" "$projectDir\bin\StreamBravia-TV.apk"

Write-Output "Step 7: Signing with apksigner..."
& "$bt\apksigner.bat" sign --ks $ks --ks-pass pass:android --ks-key-alias androiddebugkey "$projectDir\bin\StreamBravia-TV.apk"
if ($LASTEXITCODE -ne 0) { throw "signing failed with code $LASTEXITCODE" }

Write-Output "Step 8: Verifying signature..."
& "$bt\apksigner.bat" verify "$projectDir\bin\StreamBravia-TV.apk"
if ($LASTEXITCODE -ne 0) { throw "signature verify failed" }

Write-Output "SUCCESS! StreamBravia-TV.apk with Media3 ExoPlayer (Pure GPU Zero-Copy Hardware Acceleration) built and verified!"
