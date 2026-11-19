param(
    [ValidateSet("release", "debug")]
    [string]$BuildType = "release"
)

$ErrorActionPreference = "Stop"

$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
$LibboxAar = Join-Path $WorkspaceRoot "app\libs\libbox.aar"

if (-not $env:JAVA_HOME -or -not (Test-Path (Join-Path $env:JAVA_HOME "bin\java.exe"))) {
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) {
        $env:JAVA_HOME = Split-Path -Parent (Split-Path -Parent $javaCmd.Source)
    } else {
        throw "JDK 17+ not found. Please set JAVA_HOME or add java to PATH."
    }
}

if (-not $env:ANDROID_HOME) {
    if ($env:ANDROID_SDK_ROOT) {
        $env:ANDROID_HOME = $env:ANDROID_SDK_ROOT
    } elseif ($env:LOCALAPPDATA -and (Test-Path (Join-Path $env:LOCALAPPDATA "Android\Sdk"))) {
        $env:ANDROID_HOME = Join-Path $env:LOCALAPPDATA "Android\Sdk"
    }
}
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"

if (-not (Test-Path $LibboxAar)) {
    Write-Host "libbox.aar not found, building Go core AAR first..."
    & (Join-Path $PSScriptRoot "build_libbox.ps1")
}

$GradleTask = if ($BuildType -eq "debug") { ":app:assembleDebug" } else { ":app:assembleRelease" }

Push-Location $WorkspaceRoot
try {
    Write-Host "Building SingScale ($BuildType) APK via $GradleTask..."
    & .\gradlew.bat $GradleTask --no-daemon
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle $GradleTask failed with exit code $LASTEXITCODE"
    }

    $OutputDir = Join-Path $WorkspaceRoot "app\build\outputs\apk\$BuildType"
    $BuiltApk = Get-ChildItem -Path $OutputDir -Filter "*.apk" -ErrorAction SilentlyContinue |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if (-not $BuiltApk) {
        throw "Expected APK not found under $OutputDir"
    }

    $sizeMb = [Math]::Round($BuiltApk.Length / 1MB, 2)
    Write-Host "SUCCESS: APK generated at $($BuiltApk.FullName) ($sizeMb MB)"
} finally {
    Pop-Location
}
