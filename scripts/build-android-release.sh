#!/usr/bin/env bash
# KnotYup Android 正式版打包：assembleRelease（knotyup.keystore 签名）→ dist/ 带时间戳
# 用法：./scripts/build-android-release.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"; mkdir -p "$DIST"
STAMP="$(date +%Y%m%d_%H%M)"

echo "==> Android 正式版（assembleRelease，无任何调试能力）"
cd "$ROOT/knot-android"
./gradlew assembleRelease

APK="$ROOT/knot-android/app/build/outputs/apk/release/app-release.apk"
[ -f "$APK" ] || { echo "错误：未找到产物 $APK"; exit 1; }
cp "$APK" "$DIST/KnotYup_android_release_${STAMP}.apk"

echo ""
echo "打包完成："
ls -lh "$DIST" | grep "android_release_${STAMP}"
