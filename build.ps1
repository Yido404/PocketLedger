param(
    [string]$AndroidJar = "",
    [string]$BuildTools = ""
)

$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$source = Join-Path $project 'app\src\main'
$work = Join-Path $project 'build'
$localTools = Join-Path $project '.tools'
$output = Join-Path $project 'deliverables'

if (!$AndroidJar) { $AndroidJar = Join-Path $localTools 'android-35\android.jar' }
if (!$BuildTools) { $BuildTools = Join-Path $localTools 'android-15' }
if (!(Test-Path -LiteralPath $AndroidJar) -or !(Test-Path -LiteralPath (Join-Path $BuildTools 'aapt2.exe'))) {
    throw 'Supply the Android 35 android.jar and Build Tools 35.0.0 directories with -AndroidJar and -BuildTools.'
}

New-Item -ItemType Directory -Force -Path $work,$localTools,$output | Out-Null
$generated = Join-Path $work 'generated'
$classes = Join-Path $work 'classes'
$dex = Join-Path $work 'dex'
New-Item -ItemType Directory -Force -Path $generated,$classes,$dex | Out-Null
$resources = Join-Path $work 'resources.zip'
$unsigned = Join-Path $work 'unsigned.apk'
$aligned = Join-Path $work 'aligned.apk'
$apk = Join-Path $output 'PocketLedger.apk'
$key = Join-Path $localTools 'pocket-ledger.p12'
$ocr = Join-Path $localTools 'ocr'
$ocrAssets = Join-Path $ocr 'assets'
$ocrPackage = Join-Path $ocr 'tesseract.aar'
$ocrLibrary = Join-Path $ocr 'aar'
$ocrClasses = Join-Path $ocrLibrary 'classes.jar'

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$Executable failed with exit code $LASTEXITCODE" }
}

function Get-Dependency([string]$Url, [string]$Path, [string]$Hash) {
    if (!(Test-Path -LiteralPath $Path)) {
        Invoke-Checked 'curl.exe' @('-fsSL','--retry','2',$Url,'-o',"$Path.download")
        Move-Item -LiteralPath "$Path.download" -Destination $Path
    }
    if ((Get-FileHash -LiteralPath $Path).Hash -ne $Hash) { throw "Checksum mismatch for $Path" }
}

New-Item -ItemType Directory -Force -Path $ocr,$ocrAssets,$ocrLibrary | Out-Null
Get-Dependency 'https://jitpack.io/cz/adaptech/tesseract4android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar' `
    $ocrPackage 'BCE5D6413A1A5AE3D7240033FBBC851BA3217D0A08D9769400E17A077F42CB2A'
Get-Dependency 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/chi_sim.traineddata' `
    (Join-Path $ocrAssets 'chi_sim.traineddata') 'A5FCB6F0DB1E1D6D8522F39DB4E848F05984669172E584E8D76B6B3141E1F730'
Get-Dependency 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/4.1.0/eng.traineddata' `
    (Join-Path $ocrAssets 'eng.traineddata') '7D4322BD2A7749724879683FC3912CB542F19906C83BCC1A52132556427170B2'
Get-Dependency 'https://raw.githubusercontent.com/adaptech-cz/Tesseract4Android/master/LICENSE' `
    (Join-Path $ocrAssets 'Tesseract4Android-LICENSE.txt') 'C71D239DF91726FC519C6EB72D318EC65820627232B2F796219E87DCF35D0AB4'
Get-Dependency 'https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/main/LICENSE' `
    (Join-Path $ocrAssets 'tessdata-fast-LICENSE.txt') 'CFC7749B96F63BD31C3C42B5C471BF756814053E847C10F3EB003417BC523D30'
Invoke-Checked 'tar.exe' @('-xf',$ocrPackage,'-C',$ocrLibrary)
$apkLib = Join-Path $work 'ocr-libs'
New-Item -ItemType Directory -Force -Path (Join-Path $apkLib 'lib') | Out-Null
Get-ChildItem -LiteralPath (Join-Path $ocrLibrary 'jni') | Copy-Item -Destination (Join-Path $apkLib 'lib') -Recurse -Force

Invoke-Checked (Join-Path $BuildTools 'aapt2.exe') @('compile','--dir',(Join-Path $source 'res'),'-o',$resources)
Invoke-Checked (Join-Path $BuildTools 'aapt2.exe') @('link','-I',$AndroidJar,'--manifest',
    (Join-Path $source 'AndroidManifest.xml'),'--java',$generated,'-A',$ocrAssets,'-o',$unsigned,$resources)
$javaFiles = @(Get-ChildItem -LiteralPath (Join-Path $source 'java'),$generated -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName)
Invoke-Checked 'javac' (@('-encoding','UTF-8','--release','8','-classpath',"$AndroidJar;$ocrClasses",'-d',$classes) + $javaFiles)
$archive = Join-Path $work 'classes.jar'
Invoke-Checked 'jar' @('cf',$archive,'-C',$classes,'.')
Invoke-Checked (Join-Path $BuildTools 'd8.bat') @('--release','--min-api','28','--lib',$AndroidJar,'--output',$dex,$archive,$ocrClasses)
$dexFiles = @(Get-ChildItem -LiteralPath $dex -Filter 'classes*.dex' | ForEach-Object { '-C'; $dex; $_.Name })
Invoke-Checked 'jar' (@('uf',$unsigned,'-C',$apkLib,'lib') + $dexFiles)
Invoke-Checked (Join-Path $BuildTools 'zipalign.exe') @('-f','4',$unsigned,$aligned)
if (!(Test-Path -LiteralPath $key)) {
    Invoke-Checked 'keytool' @('-genkeypair','-keystore',$key,'-storetype','PKCS12','-storepass','android',
        '-keypass','android','-alias','pocketledger','-keyalg','RSA','-keysize','2048','-validity','10000',
        '-dname','CN=Pocket Ledger Local Build')
}
Invoke-Checked (Join-Path $BuildTools 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias','pocketledger',
    '--ks-pass','pass:android','--v4-signing-enabled','false','--out',$apk,$aligned)
Invoke-Checked (Join-Path $BuildTools 'apksigner.bat') @('verify','--verbose',$apk)
Get-Item -LiteralPath $apk | Select-Object FullName,Length
