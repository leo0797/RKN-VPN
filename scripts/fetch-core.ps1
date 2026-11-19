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
$manifestPath = Join-Path $PSScriptRoot "core.sha256"

New-Item -ItemType Directory -Force -Path $libs, $arm64, $armv7 | Out-Null

$expectedHashes = @{}
foreach ($line in Get-Content -LiteralPath $manifestPath) {
    if ($line -match '^\s*([0-9a-fA-F]{64})\s+(\S+)\s*$') {
        $expectedHashes[$Matches[2]] = $Matches[1].ToLowerInvariant()
    }
}

# Проверяем и свежескачанные, и уже лежащие на диске файлы: кэш не должен
# позволять собирать APK с подменённым ядром.
function Test-ArtifactHash {
    param([string]$RelativePath, [string]$Path)

    if (-not $expectedHashes.ContainsKey($RelativePath)) {
        throw "Нет SHA-256 для $RelativePath в scripts/core.sha256"
    }
    if ((-not (Test-Path -LiteralPath $Path)) -or (Get-Item -LiteralPath $Path).Length -eq 0) {
        return $false
    }
    $expected = $expectedHashes[$RelativePath]
    $actual = (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($actual -ne $expected) {
        Write-Warning "SHA-256 не совпал для $RelativePath`: ожидалось $expected, получено $actual"
        Remove-Item -LiteralPath $Path -Force -ErrorAction SilentlyContinue
        return $false
    }
    return $true
}

$hevRel = "app/libs/hev-socks5-tunnel.aar"
$aar = Join-Path $libs "hev-socks5-tunnel.aar"
if (-not (Test-ArtifactHash -RelativePath $hevRel -Path $aar)) {
    Write-Host "==> hev-socks5-tunnel $hevVersion"
    Invoke-WebRequest `
        "https://github.com/heiher/hev-socks5-tunnel/releases/download/$hevVersion/hev-socks5-tunnel.aar" `
        -OutFile $aar
    if (-not (Test-ArtifactHash -RelativePath $hevRel -Path $aar)) {
        throw "SHA-256 не совпал для hev-socks5-tunnel.aar"
    }
}

function Get-Mihomo {
    param([string]$Abi, [string]$Asset, [string]$RelativePath, [string]$Out)

    if (Test-ArtifactHash -RelativePath $RelativePath -Path $Out) {
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
    if (-not (Test-ArtifactHash -RelativePath $RelativePath -Path $Out)) {
        throw "SHA-256 не совпал для $RelativePath"
    }
}

Write-Host "==> mihomo $mihomoVersion"
Get-Mihomo -Abi "arm64-v8a" -Asset "mihomo-android-arm64-v8-$mihomoVersion.gz" `
    -RelativePath "app/src/main/jniLibs/arm64-v8a/libmihomo.so" `
    -Out (Join-Path $arm64 "libmihomo.so")
Get-Mihomo -Abi "armeabi-v7a" -Asset "mihomo-android-armv7-$mihomoVersion.gz" `
    -RelativePath "app/src/main/jniLibs/armeabi-v7a/libmihomo.so" `
    -Out (Join-Path $armv7 "libmihomo.so")

Write-Host "==> Done"
Get-Item $aar, (Join-Path $arm64 "libmihomo.so"), (Join-Path $armv7 "libmihomo.so") |
    Select-Object FullName, Length | Format-Table -AutoSize
