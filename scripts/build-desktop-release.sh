#!/usr/bin/env bash
# KnotYup macOS 正式版打包：cargo tauri build（调试能力编译期剔除）→ dist/ 带时间戳
# 用法：./scripts/build-desktop-release.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"; mkdir -p "$DIST"
STAMP="$(date +%Y%m%d_%H%M)"

echo "==> macOS 正式版（cargo tauri build，无任何调试能力）"
cd "$ROOT/knot-desktop"
cargo tauri build

DMG="$ROOT/knot-desktop/src-tauri/target/release/bundle/dmg/KnotYup_0.1.0_aarch64.dmg"
[ -f "$DMG" ] || { echo "错误：未找到产物 $DMG"; exit 1; }
cp "$DMG" "$DIST/KnotYup_macos_release_${STAMP}_aarch64.dmg"

echo ""
echo "打包完成："
ls -lh "$DIST" | grep "macos_release_${STAMP}"
