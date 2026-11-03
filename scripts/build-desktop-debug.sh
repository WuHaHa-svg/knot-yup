#!/usr/bin/env bash
# KnotYup macOS 测试版打包：cargo tauri build --debug（含调试日志页）→ dist/ 带时间戳
# 用法：./scripts/build-desktop-debug.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"; mkdir -p "$DIST"
STAMP="$(date +%Y%m%d_%H%M)"

echo "==> macOS 测试版（cargo tauri build --debug，含调试日志页）"
cd "$ROOT/knot-desktop"
cargo tauri build --debug

DMG="$ROOT/knot-desktop/src-tauri/target/debug/bundle/dmg/KnotYup_0.1.0_aarch64.dmg"
[ -f "$DMG" ] || { echo "错误：未找到产物 $DMG"; exit 1; }
cp "$DMG" "$DIST/KnotYup_macos_debug_${STAMP}_aarch64.dmg"

echo ""
echo "打包完成："
ls -lh "$DIST" | grep "macos_debug_${STAMP}"
