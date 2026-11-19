$ErrorActionPreference = "Stop"

$WorkspaceRoot = Split-Path -Parent $PSScriptRoot
$CoreDir = Join-Path $WorkspaceRoot "core\sing-box"
$OutputAar = Join-Path $WorkspaceRoot "app\libs\libbox.aar"

if (-not (Get-Command go -ErrorAction SilentlyContinue)) {
    throw "Go toolchain not found on PATH. Please install Go 1.24+ and ensure 'go' is available on PATH."
}

$GoPath = (& go env GOPATH).Trim()
if ($GoPath -and (Test-Path (Join-Path $GoPath "bin"))) {
    $env:PATH = "$(Join-Path $GoPath 'bin');$env:PATH"
}

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
    } else {
        throw "ANDROID_HOME is not set and default Android SDK directory was not found."
    }
}
$env:ANDROID_SDK_HOME = $env:ANDROID_HOME

$NdkVersion = "28.0.13004108"
$NdkDir = if ($env:ANDROID_NDK_HOME -and (Test-Path (Join-Path $env:ANDROID_NDK_HOME "source.properties"))) {
    $env:ANDROID_NDK_HOME
} else {
    Join-Path $env:ANDROID_HOME "ndk\$NdkVersion"
}

if (-not (Test-Path (Join-Path $NdkDir "source.properties"))) {
    $foundNdk = Get-ChildItem (Join-Path $env:ANDROID_HOME "ndk") -Directory -ErrorAction SilentlyContinue |
        Sort-Object Name -Descending |
        Select-Object -First 1
    if ($foundNdk) {
        $NdkDir = $foundNdk.FullName
    } else {
        throw "Android NDK not found under $env:ANDROID_HOME\ndk. Install NDK $NdkVersion (or set ANDROID_NDK_HOME)."
    }
}

$env:ANDROID_NDK_HOME = $NdkDir
$env:NDK = $NdkDir
$LlvmBin = Join-Path $NdkDir "toolchains\llvm\prebuilt\windows-x86_64\bin"
if (Test-Path $LlvmBin) {
    $env:PATH = "$env:JAVA_HOME\bin;$LlvmBin;$env:PATH"
} else {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

$GomobileCmd = Get-Command gomobile -ErrorAction SilentlyContinue
if (-not $GomobileCmd) {
    throw "SagerNet gomobile not found on PATH. Install it via: go install -v github.com/sagernet/gomobile/cmd/gomobile@latest && gomobile init"
}

$BuildTags = "with_gvisor,with_quic,with_wireguard,with_utls,with_clash_api,with_tailscale,ts_omit_logtail,ts_omit_ssh,ts_omit_drive,ts_omit_taildrop,ts_omit_webclient,ts_omit_doctor,ts_omit_capture,ts_omit_kube,ts_omit_aws,ts_omit_synology,ts_omit_bird,with_low_memory,badlinkname,tfogo_checklinkname0"

Push-Location $CoreDir
try {
    Write-Host "Step 1/2: Verifying SingScale Go core + Tailscale extensions..."
    $prevGoos = $env:GOOS
    $prevGoarch = $env:GOARCH
    $prevCgo = $env:CGO_ENABLED
    $env:GOOS = "linux"
    $env:GOARCH = "arm64"
    $env:CGO_ENABLED = "0"
    & go build -tags $BuildTags ./experimental/libbox
    if ($LASTEXITCODE -ne 0) { throw "Go verification build failed" }
    $env:GOOS = $prevGoos
    $env:GOARCH = $prevGoarch
    $env:CGO_ENABLED = $prevCgo

    Write-Host "Step 2/2: Binding native Android AAR (arm64-v8a + x86_64) via SagerNet gomobile..."
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $OutputAar) | Out-Null
    & $GomobileCmd.Source bind -v `
        -target "android/arm64,android/amd64" `
        -androidapi 26 `
        -javapkg "io.nekohasekai" `
        -libname "box" `
        -trimpath `
        -buildvcs=false `
        -ldflags "-X 'github.com/sagernet/sing-box/constant.Version=1.14.4-singscale' -X runtime.godebugDefault=multipathtcp=0,tlssha1=1 -checklinkname=0 -s -w -buildid=" `
        -tags $BuildTags `
        -o $OutputAar `
        ./experimental/libbox
    if ($LASTEXITCODE -ne 0) { throw "gomobile bind failed" }
    Write-Host "Successfully built $OutputAar ($([Math]::Round((Get-Item $OutputAar).Length / 1MB, 2)) MB)"
} finally {
    Pop-Location
}
