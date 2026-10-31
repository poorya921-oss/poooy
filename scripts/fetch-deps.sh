#!/usr/bin/env bash
# Downloads the two external pieces POOOY needs:
#   1) libv2ray.aar            (Xray-core, gomobile build from 2dust/AndroidLibXrayLite)
#   2) hev-socks5-tunnel       (native tun2socks, built by the NDK as part of the Gradle build)
# Optional: HEV_REF=<tag-or-commit> to pin hev-socks5-tunnel, AAR_URL=<url> to use another AAR.
set -euo pipefail
cd "$(dirname "$0")/.."

AAR=app/libs/libv2ray.aar
HEV=app/src/main/jni/hev-socks5-tunnel
AAR_URL="${AAR_URL:-https://github.com/2dust/AndroidLibXrayLite/releases/latest/download/libv2ray.aar}"

mkdir -p app/libs
if [ -f "$AAR" ]; then
  echo "[ok] $AAR already present"
else
  echo "[..] downloading $AAR_URL"
  curl -fL --retry 3 -o "$AAR" "$AAR_URL"
fi

if [ -f "$HEV/Android.mk" ]; then
  echo "[ok] $HEV already present"
else
  echo "[..] cloning hev-socks5-tunnel (with submodules)"
  git clone --recursive https://github.com/heiher/hev-socks5-tunnel "$HEV"
  if [ -n "${HEV_REF:-}" ]; then
    git -C "$HEV" checkout "$HEV_REF"
    git -C "$HEV" submodule update --init --recursive
  fi
fi
echo "Done. Next: open the project in Android Studio, or run: gradle assembleRelease -Ppooy.debugSign=true"
