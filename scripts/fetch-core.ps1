# Downloads the mihomo core and the tun2socks library for the APK build.
# Windows PowerShell 5+ helper. GitHub Actions uses scripts/fetch-core.sh.
$ErrorActionPreference = "Stop"
$ProgressPreference = "SilentlyContinue"

$mihomoVersion = if ($env:MIHOMO_VERSION) { $env:MIHOMO_VERSION } else { "v1.19.34" }
$hevVersion = if ($env:HEV_VERSION) { $env:HEV_VERSION } else { "2.19.3" }

$root = Split-Path -Parent $PSScriptRoot
$libs = Join-Path $root "app\libs"
$jni = Join-Path $root "app\src\main\jniLibs"
$arm64 = Join-Path $jni "arm64-v8a"
$armv7 = Join-Path $jni "armeabi-v7a"

New-Item -ItemType Directory -Force -Path $libs, $arm64, $armv7 | Out-Null

$aar = Join-Path $libs "hev-socks5-tunnel.aar"
if (-not (Test-Path $aar) -or (Get-Item $aar).Length -eq 0) {
    Write-Host "==> hev-socks5-tunnel $hevVersion"
    Invoke-WebRequest `
        "https://github.com/heiher/hev-socks5-tunnel/releases/download/$hevVersion/hev-socks5-tunnel.aar" `
        -OutFile $aar
}

function Get-Mihomo {
    param([string]$Abi, [string]$Asset, [string]$Out)

    if ((Test-Path $Out) -and (Get-Item $Out).Length -gt 0) {
        Write-Host "    $Abi already present"
        return
    }

    Write-Host "    $Abi`: $Asset"
    $tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("mihomo-" + [guid]::NewGuid())
    New-Item -ItemType Directory -Force -Path $tmp | Out-Null
    try {
        $gz = Join-Path $tmp "mihomo.gz"
        Invoke-WebRequest `
            "https://github.com/MetaCubeX/mihomo/releases/download/$mihomoVersion/$Asset" `
            -OutFile $gz

        $src = [System.IO.File]::OpenRead($gz)
        try {
            $zip = [System.IO.Compression.GZipStream]::new(
                $src, [System.IO.Compression.CompressionMode]::Decompress)
            try {
                $dst = [System.IO.File]::Create($Out)
                try { $zip.CopyTo($dst) } finally { $dst.Dispose() }
            } finally { $zip.Dispose() }
        } finally { $src.Dispose() }
    } finally {
        Remove-Item -Recurse -Force $tmp -ErrorAction SilentlyContinue
    }
}

Write-Host "==> mihomo $mihomoVersion"
Get-Mihomo -Abi "arm64-v8a" -Asset "mihomo-android-arm64-v8-$mihomoVersion.gz" `
    -Out (Join-Path $arm64 "libmihomo.so")
Get-Mihomo -Abi "armeabi-v7a" -Asset "mihomo-android-armv7-$mihomoVersion.gz" `
    -Out (Join-Path $armv7 "libmihomo.so")

Write-Host "==> Done"
Get-Item $aar, (Join-Path $arm64 "libmihomo.so"), (Join-Path $armv7 "libmihomo.so") |
    Select-Object FullName, Length | Format-Table -AutoSize
