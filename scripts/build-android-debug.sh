#!/usr/bin/env bash
# KnotYup Android 测试版打包：assembleDebug → dist/ 带时间戳
# 用法：./scripts/build-android-debug.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"; mkdir -p "$DIST"
STAMP="$(date +%Y%m%d_%H%M)"

echo "==> Android 测试版（assembleDebug）"
cd "$ROOT/knot-android"
./gradlew assembleDebug

APK="$ROOT/knot-android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK" ] || { echo "错误：未找到产物 $APK"; exit 1; }
cp "$APK" "$DIST/KnotYup_android_debug_${STAMP}.apk"

echo ""
echo "打包完成："
ls -lh "$DIST" | grep "android_debug_${STAMP}"
