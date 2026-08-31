[CmdletBinding()]
param(
    [ValidateSet('NonRoot', 'Root')]
    [string]$Variant = 'NonRoot',

    [string]$JavaHome,

    [string]$AndroidSdk
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $PSScriptRoot
$variantDirectory = if ($Variant -eq 'Root') { 'root' } else { 'nonRoot' }
$gradleTask = ":app:assemble${Variant}Release"

function Resolve-JavaHome {
    param([string]$ConfiguredJavaHome)

    $candidates = @(
        $ConfiguredJavaHome,
        $env:JAVA_HOME,
        'D:\develop_tool\Android\Android Studio\jbr',
        (Join-Path $env:ProgramFiles 'Android\Android Studio\jbr')
    ) | Where-Object { $_ }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'bin\java.exe')) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw '未找到可用的 JDK。请通过 -JavaHome 指定 Android Studio JBR 或 JDK 17。'
}

function Resolve-AndroidSdk {
    param([string]$ConfiguredAndroidSdk)

    $candidates = @(
        $ConfiguredAndroidSdk,
        $env:ANDROID_SDK_ROOT,
        $env:ANDROID_HOME,
        (Join-Path $env:LOCALAPPDATA 'Android\Sdk')
    ) | Where-Object { $_ }

    foreach ($candidate in $candidates) {
        if (Test-Path -LiteralPath (Join-Path $candidate 'build-tools')) {
            return (Resolve-Path -LiteralPath $candidate).Path
        }
    }

    throw '未找到 Android SDK。请通过 -AndroidSdk 指定 SDK 路径。'
}

$resolvedJavaHome = Resolve-JavaHome -ConfiguredJavaHome $JavaHome
$resolvedAndroidSdk = Resolve-AndroidSdk -ConfiguredAndroidSdk $AndroidSdk
$buildTools = Get-ChildItem -LiteralPath (Join-Path $resolvedAndroidSdk 'build-tools') -Directory |
    Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'apksigner.bat') } |
    Sort-Object { [version]$_.Name } -Descending |
    Select-Object -First 1

if (-not $buildTools) {
    throw "Android SDK 中没有可用的 build-tools: $resolvedAndroidSdk"
}

$debugKeystore = Join-Path $env:USERPROFILE '.android\debug.keystore'
$keytool = Join-Path $resolvedJavaHome 'bin\keytool.exe'
$apksigner = Join-Path $buildTools.FullName 'apksigner.bat'
$zipalign = Join-Path $buildTools.FullName 'zipalign.exe'

if (-not (Test-Path -LiteralPath $debugKeystore)) {
    Write-Host '未找到本机 Android Debug 密钥，正在生成固定的本地测试密钥……'
    New-Item -ItemType Directory -Path (Split-Path -Parent $debugKeystore) -Force | Out-Null
    & $keytool -genkeypair -keystore $debugKeystore -storepass android -alias androiddebugkey `
        -keypass android -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 10000
    if ($LASTEXITCODE -ne 0) {
        throw "生成 Android Debug 密钥失败，退出码: $LASTEXITCODE"
    }
}

$env:JAVA_HOME = $resolvedJavaHome
$env:ANDROID_SDK_ROOT = $resolvedAndroidSdk
$env:Path = "$resolvedJavaHome\bin;$env:Path"
$audioHapticsSdk = Join-Path $projectRoot '.deps\moonlight-audio-haptics'
if (Test-Path -LiteralPath $audioHapticsSdk) {
    $env:AUDIO_HAPTICS_SDK_DIR = $audioHapticsSdk
}
$env:JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Dsun.stdout.encoding=UTF-8 -Dsun.stderr.encoding=UTF-8'

Write-Host "开始构建 $Variant Release APK……"
Push-Location $projectRoot
try {
    & .\gradlew.bat $gradleTask --no-daemon --stacktrace --console=plain --max-workers=4 `
        -x :framegen:extractReleaseAnnotations `
        -x :framegen:syncReleaseLibJars `
        -x :framegen:lintVitalRelease
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle 构建失败，退出码: $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}

$unsignedApk = Join-Path $projectRoot "app\build\outputs\apk\$variantDirectory\release\app-$variantDirectory-release-unsigned.apk"
if (-not (Test-Path -LiteralPath $unsignedApk)) {
    throw "构建完成但未找到 unsigned APK: $unsignedApk"
}

$metadataPath = Join-Path $projectRoot "app\build\outputs\apk\$variantDirectory\release\output-metadata.json"
$versionName = if (Test-Path -LiteralPath $metadataPath) {
    (Get-Content -LiteralPath $metadataPath -Encoding UTF8 -Raw | ConvertFrom-Json).elements[0].versionName
} else {
    'local'
}

$artifactDirectory = Join-Path $projectRoot 'artifacts\local-build'
New-Item -ItemType Directory -Path $artifactDirectory -Force | Out-Null
$outputApk = Join-Path $artifactDirectory "moonlight-vplus-$versionName-$variantDirectory-release-local-debug-signed.apk"

Write-Host '正在使用本机固定 Debug 密钥签名……'
& $apksigner sign --ks $debugKeystore --ks-key-alias androiddebugkey `
    --ks-pass pass:android --key-pass pass:android --out $outputApk $unsignedApk
if ($LASTEXITCODE -ne 0) {
    throw "APK 签名失败，退出码: $LASTEXITCODE"
}

& $apksigner verify --verbose --print-certs $outputApk
if ($LASTEXITCODE -ne 0) {
    throw "APK 签名验证失败，退出码: $LASTEXITCODE"
}

& $zipalign -c 4 $outputApk
if ($LASTEXITCODE -ne 0) {
    throw "APK 对齐验证失败，退出码: $LASTEXITCODE"
}

$sha256 = (Get-FileHash -LiteralPath $outputApk -Algorithm SHA256).Hash
Write-Host "可安装 APK: $outputApk"
Write-Host "SHA-256: $sha256"
