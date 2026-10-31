# Windows version of fetch-deps.sh (requires git and curl.exe, both bundled with current Windows 10/11)
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

$aar = "app/libs/libv2ray.aar"
$hev = "app/src/main/jni/hev-socks5-tunnel"
$aarUrl = if ($env:AAR_URL) { $env:AAR_URL } else { "https://github.com/2dust/AndroidLibXrayLite/releases/latest/download/libv2ray.aar" }

New-Item -ItemType Directory -Force -Path "app/libs" | Out-Null
if (Test-Path $aar) { Write-Host "[ok] $aar already present" }
else { Write-Host "[..] downloading $aarUrl"; curl.exe -fL --retry 3 -o $aar $aarUrl }

if (Test-Path "$hev/Android.mk") { Write-Host "[ok] $hev already present" }
else {
  Write-Host "[..] cloning hev-socks5-tunnel (with submodules)"
  git clone --recursive https://github.com/heiher/hev-socks5-tunnel $hev
  if ($env:HEV_REF) { git -C $hev checkout $env:HEV_REF; git -C $hev submodule update --init --recursive }
}
Write-Host "Done. Next: open the project in Android Studio."
