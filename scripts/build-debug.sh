#!/usr/bin/env bash
# KnotYup 测试版打包脚本：macOS（tauri build --debug）+ Android（assembleDebug）
# 产物统一拷贝到项目根目录 dist/，带时间戳命名
# 用法：./scripts/build-debug.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
mkdir -p "$DIST"
STAMP="$(date +%Y%m%d_%H%M)"

echo "==> [1/2] macOS 测试版（cargo tauri build --debug，含调试日志页）"
cd "$ROOT/knot-desktop"
cargo tauri build --debug

echo "==> [2/2] Android 测试版（assembleDebug）"
cd "$ROOT/knot-android"
./gradlew assembleDebug

echo "==> 收集产物到 dist/"
DMG="$ROOT/knot-desktop/src-tauri/target/debug/bundle/dmg/KnotYup_0.1.0_aarch64.dmg"
APK="$ROOT/knot-android/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$DMG" ] || { echo "错误：未找到 macOS 产物 $DMG"; exit 1; }
[ -f "$APK" ] || { echo "错误：未找到 Android 产物 $APK"; exit 1; }

cp "$DMG" "$DIST/KnotYup_debug_${STAMP}_aarch64.dmg"
cp "$APK" "$DIST/KnotYup_debug_${STAMP}.apk"

echo ""
echo "打包完成："
ls -lh "$DIST" | grep "debug_${STAMP}"
