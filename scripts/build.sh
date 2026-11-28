#!/usr/bin/env bash
# KnotYup 统一打包脚本
#
# 用法：./scripts/build.sh <平台> <版本>
#   平台：a（Android）| m（macOS）
#   版本：dev        → 打包测试版（debug，不改版本号）
#         其他(如 0.6.2) → 打包正式版（release，并把版本号同步写入双端真相源）
#
# 示例：
#   ./scripts/build.sh a dev
#   ./scripts/build.sh a 0.6.2
#   ./scripts/build.sh m dev
#   ./scripts/build.sh m 0.6.2
#
# 产物统一放在项目根目录 dist/，文件名带模式/版本与时间戳。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST="$ROOT/dist"
mkdir -p "$DIST"
STAMP="$(date +%Y%m%d_%H%M)"

PLATFORM="${1:-}"
VERSION="${2:-}"

usage() {
  echo "用法: $0 <a|m> <dev|版本号>"
  echo "  a dev      Android 测试版"
  echo "  a 0.6.2    Android 正式版 0.6.2"
  echo "  m dev          macOS 测试版"
  echo "  m 0.6.2        macOS 正式版 0.6.2"
}

[ -n "$PLATFORM" ] || { echo "错误：缺少参数一（平台）"; usage; exit 1; }
[ -n "$VERSION" ] || { echo "错误：缺少参数二（版本）"; usage; exit 1; }

case "$PLATFORM" in
  a) PLATFORM="android" ;;
  m) PLATFORM="mac" ;;
  *) echo "错误：参数一必须是 a 或 m，收到: $PLATFORM"; usage; exit 1 ;;
esac

# 版本文件：正式版要一次性同步全部 5 处，否则 Cargo.lock 与 Cargo.toml 版本不一致
ANDROID_GRADLE="$ROOT/knot-android/app/build.gradle.kts"
DESKTOP_CARGO="$ROOT/knot-desktop/src-tauri/Cargo.toml"
DESKTOP_LOCK="$ROOT/knot-desktop/src-tauri/Cargo.lock"
CORE_CARGO="$ROOT/knot-core/Cargo.toml"
CORE_LOCK="$ROOT/knot-core/Cargo.lock"

if [ "$VERSION" = "dev" ]; then
  MODE="dev"
  echo "==> [$PLATFORM] 测试版（dev，版本号保持不变）"
  # dev 从对应端真相源读取当前版本（仅用于 macOS 找 DMG 产物名）
  if [ "$PLATFORM" = "android" ]; then
    CURRENT_VERSION="$(sed -nE 's/.*val appVersion = "([^"]+)".*/\1/p' "$ANDROID_GRADLE" | head -1)"
  else
    CURRENT_VERSION="$(sed -nE 's/^version = "([^"]+)"/\1/p' "$DESKTOP_CARGO" | head -1)"
  fi
else
  MODE="release"
  if ! echo "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+$'; then
    echo "错误：版本号格式应为 x.y.z，收到: $VERSION"; exit 1
  fi
  echo "==> 正式版 ${VERSION}: 同步写入全部版本文件"
  python3 - "$VERSION" \
    "$ANDROID_GRADLE" "$DESKTOP_CARGO" "$DESKTOP_LOCK" "$CORE_CARGO" "$CORE_LOCK" <<'PY'
import re, sys
ver = sys.argv[1]
android_gradle, desktop_cargo, desktop_lock, core_cargo, core_lock = sys.argv[2:7]

def rewrite(path, pattern, label, repl=None):
    s = open(path, encoding="utf-8").read()
    r = repl if repl is not None else (lambda m: m.group(1) + ver + m.group(2))
    s2, n = re.subn(pattern, r, s, count=1)
    if n != 1:
        sys.exit(f"错误：{label} 未匹配到版本定义（{path}）")
    open(path, "w", encoding="utf-8").write(s2)
    print(f"  {label} → {ver}")

# Android 应用版本
rewrite(android_gradle, r'(val appVersion = ")[^"]+(")', "Android appVersion")
# 桌面应用版本（Cargo.toml package 段首个 version）
rewrite(desktop_cargo, r'(?m)^version = "[^"]+"$', "desktop Cargo.toml",
        repl=lambda m: 'version = "' + ver + '"')
# knot-core 库版本
rewrite(core_cargo, r'(?m)^version = "[^"]+"$', "knot-core Cargo.toml",
        repl=lambda m: 'version = "' + ver + '"')
# 两个 Cargo.lock 里 knot-desktop / knot-core 段紧跟的 version（不动 lock 文件格式版本 3.0.0）
def rewrite_lock(path, pkg, label):
    s = open(path, encoding="utf-8").read()
    pat = re.compile(r'(name = "' + re.escape(pkg) + r'"\nversion = ")[^"]+(")')
    s2, n = pat.subn(lambda m: m.group(1) + ver + m.group(2), s, count=1)
    if n != 1:
        sys.exit(f"错误：{label} 未匹配到 {pkg} 段（{path}）")
    open(path, "w", encoding="utf-8").write(s2)
    print(f"  {label} → {ver}")

rewrite_lock(desktop_lock, "knot-desktop", "desktop Cargo.lock")
rewrite_lock(desktop_lock, "knot-core", "desktop lock knot-core")
rewrite_lock(core_lock, "knot-core", "core Cargo.lock")
PY
  CURRENT_VERSION="$VERSION"
fi
[ -n "$CURRENT_VERSION" ] || { echo "错误：无法读取版本号"; exit 1; }

if [ "$PLATFORM" = "android" ]; then
  echo "==> 执行 Android $([ "$MODE" = dev ] && echo assembleDebug || echo assembleRelease)"
  cd "$ROOT/knot-android"
  if [ "$MODE" = "dev" ]; then
    ./gradlew assembleDebug
    APK="$ROOT/knot-android/app/build/outputs/apk/debug/app-debug.apk"
  else
    ./gradlew assembleRelease
    APK="$ROOT/knot-android/app/build/outputs/apk/release/app-release.apk"
  fi
  [ -f "$APK" ] || { echo "错误：未找到产物 $APK"; exit 1; }
  if [ "$MODE" = "dev" ]; then
    OUT="$DIST/KnotYup_android_dev_${STAMP}.apk"
  else
    OUT="$DIST/KnotYup_android_${VERSION}_${STAMP}.apk"
  fi
  cp "$APK" "$OUT"
else
  echo "==> 执行 macOS $([ "$MODE" = dev ] && echo 'cargo tauri build --debug' || echo 'cargo tauri build')"
  cd "$ROOT/knot-desktop"
  if [ "$MODE" = "dev" ]; then
    cargo tauri build --debug
    BUNDLE="$ROOT/knot-desktop/src-tauri/target/debug/bundle/dmg"
  else
    cargo tauri build
    BUNDLE="$ROOT/knot-desktop/src-tauri/target/release/bundle/dmg"
  fi
  DMG="$BUNDLE/KnotYup_${CURRENT_VERSION}_aarch64.dmg"
  [ -f "$DMG" ] || { echo "错误：未找到产物 ${DMG}（当前版本 ${CURRENT_VERSION}）"; exit 1; }
  if [ "$MODE" = "dev" ]; then
    OUT="$DIST/KnotYup_mac_dev_${STAMP}_aarch64.dmg"
  else
    OUT="$DIST/KnotYup_mac_${VERSION}_${STAMP}_aarch64.dmg"
  fi
  cp "$DMG" "$OUT"
fi

echo ""
echo "打包完成："
ls -lh "$OUT"
