#!/bin/bash
# ═══════════════════════════════════════════════════
# Doply Hub Release APK 標準產出流程
# 用法: ./scripts/release.sh [版號]
# 範例: ./scripts/release.sh 2.0.1
#       ./scripts/release.sh          (自動讀取 build.gradle.kts 版號)
# ═══════════════════════════════════════════════════

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# ── 1. 取得版號資訊 ──
GRADLE_FILE="app/build.gradle.kts"

if [ -n "${1:-}" ]; then
    VERSION_NAME="$1"
else
    VERSION_NAME=$(grep 'versionName' "$GRADLE_FILE" | head -1 | sed 's/.*"\(.*\)".*/\1/')
fi

VERSION_CODE=$(grep 'versionCode' "$GRADLE_FILE" | head -1 | sed 's/[^0-9]//g')
BUILD_DATE=$(date '+%Y-%m-%d %H:%M:%S')
BUILD_TIMESTAMP=$(date '+%Y%m%d_%H%M%S')
GIT_HASH=$(git rev-parse --short HEAD 2>/dev/null || echo "unknown")
GIT_BRANCH=$(git branch --show-current 2>/dev/null || echo "unknown")

echo "══════════════════════════════════════"
echo "  Doply Hub Release Build"
echo "  版本: v${VERSION_NAME} (${VERSION_CODE})"
echo "  Git:  ${GIT_BRANCH}@${GIT_HASH}"
echo "══════════════════════════════════════"

# ── 2. 清理並建置 Release APK ──
echo ""
echo "▶ 清理舊建置..."
./gradlew clean

echo ""
echo "▶ 建置 Release APK..."
./gradlew assembleRelease

# ── 3. 找到產出的 APK ──
APK_SOURCE="app/build/outputs/apk/release/app-release.apk"
if [ ! -f "$APK_SOURCE" ]; then
    echo "❌ 找不到 Release APK: $APK_SOURCE"
    exit 1
fi

APK_SIZE=$(du -h "$APK_SOURCE" | cut -f1)
APK_SIZE_BYTES=$(stat -f%z "$APK_SOURCE" 2>/dev/null || stat -c%s "$APK_SOURCE" 2>/dev/null)
APK_MD5=$(md5 -q "$APK_SOURCE" 2>/dev/null || md5sum "$APK_SOURCE" | cut -d' ' -f1)

echo ""
echo "✅ APK 建置成功: $APK_SIZE"

# ── 4. 建立版號資料夾並複製 APK ──
RELEASE_DIR="mov_app/v${VERSION_NAME}"
mkdir -p "$RELEASE_DIR"

APK_FILENAME="DoplyHub-v${VERSION_NAME}.apk"
cp "$APK_SOURCE" "$RELEASE_DIR/$APK_FILENAME"

echo "▶ APK 複製至: $RELEASE_DIR/$APK_FILENAME"

# ── 5. 產生版號資訊 JSON ──
VERSION_JSON="$RELEASE_DIR/version.json"
cat > "$VERSION_JSON" << JSONEOF
{
  "app": "Doply Hub",
  "packageName": "com.gimy.tv",
  "versionName": "${VERSION_NAME}",
  "versionCode": ${VERSION_CODE},
  "buildDate": "${BUILD_DATE}",
  "buildTimestamp": "${BUILD_TIMESTAMP}",
  "git": {
    "branch": "${GIT_BRANCH}",
    "commit": "${GIT_HASH}"
  },
  "apk": {
    "filename": "${APK_FILENAME}",
    "size": "${APK_SIZE}",
    "sizeBytes": ${APK_SIZE_BYTES},
    "md5": "${APK_MD5}"
  },
  "minSdk": 28,
  "targetSdk": 35,
  "supportedDevices": ["phone", "tablet", "tv"]
}
JSONEOF

echo "▶ 版號資訊寫入: $VERSION_JSON"

# ── 6. 更新最新版指標 JSON ──
LATEST_JSON="mov_app/latest.json"
cat > "$LATEST_JSON" << JSONEOF
{
  "latestVersion": "${VERSION_NAME}",
  "latestVersionCode": ${VERSION_CODE},
  "updateDate": "${BUILD_DATE}",
  "downloadPath": "v${VERSION_NAME}/${APK_FILENAME}"
}
JSONEOF

echo "▶ 最新版指標更新: $LATEST_JSON"

# ── 7. 完成 ──
echo ""
echo "══════════════════════════════════════"
echo "  ✅ Release 完成!"
echo ""
echo "  版本:  v${VERSION_NAME} (${VERSION_CODE})"
echo "  APK:   $RELEASE_DIR/$APK_FILENAME"
echo "  大小:  $APK_SIZE"
echo "  MD5:   $APK_MD5"
echo "  JSON:  $VERSION_JSON"
echo ""
echo "  下一步:"
echo "  git add mov_app/ && git commit -m \"發版 v${VERSION_NAME}\""
echo "══════════════════════════════════════"
