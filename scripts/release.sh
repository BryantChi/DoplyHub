#!/bin/bash
# ═══════════════════════════════════════════════════
# Doply Hub Release APK 標準產出流程
# 用法: ./scripts/release.sh [版號] [flags]
# 範例: ./scripts/release.sh 2.0.3
#       ./scripts/release.sh                 (自動讀取 build.gradle.kts 版號)
#       ./scripts/release.sh 2.0.3 --no-publish    (只 build，不上 GitHub Release)
#       ./scripts/release.sh --notes-file=CHANGELOG.md
#       ./scripts/release.sh --no-publish --no-git (只 build＋archive，不 commit/push)
#       ./scripts/release.sh --no-publish --no-push (commit 但不 push)
#
# 流程：build APK → 本地 archive (mov_app/) → git add + commit + push（versionCode 變動 +
#       mov_app/ 全部進目前分支）→ 上 GitHub Release（tag 指向剛推上去的發版 commit）
# 順序不能反過來：App 下載 APK 優先走 jsDelivr，網址是 @<tag>/mov_app/<tag>/<apk>，抓的是
# tag 那個 commit 裡的檔案。先發 Release 的話 tag 會落在還沒有 APK 的 commit，鏡像 404、
# 只能退回在台灣很慢的 GitHub（v3.1.18、v3.1.19 都是手動繞過這個順序才正常）。
# 所以要發 Release 就一定要 commit 並 push；--no-git／--no-push 必須搭配 --no-publish。
# 上 GitHub Release 後，舊版 App 會在下次冷啟動或下拉刷新時偵測並提示更新。
# ═══════════════════════════════════════════════════

set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$PROJECT_ROOT"

# ── 解析 flags ──
PUBLISH_GH=true
DO_GIT=true
GIT_PUSH=true
NOTES_FILE=""
NOTES_INLINE=""
GH_REPO="BryantChi/DoplyHub"
POSITIONAL=()
for arg in "$@"; do
    case "$arg" in
        --no-publish) PUBLISH_GH=false ;;
        --no-git) DO_GIT=false ;;
        --no-push) GIT_PUSH=false ;;
        --notes-file=*) NOTES_FILE="${arg#*=}" ;;
        --notes=*) NOTES_INLINE="${arg#*=}" ;;
        --repo=*) GH_REPO="${arg#*=}" ;;
        --*) echo "❌ 未知 flag: $arg"; exit 1 ;;
        *) POSITIONAL+=("$arg") ;;
    esac
done

# ── 0. 要發 Release 的前提，在 build 前全部檢查完 ──
# 原因見檔頭。發布排在 push 之後，若到那時才發現發不出去，發版 commit 已經推上去、Release
# 卻沒建，卡在一半；所以 gh 是否可用也在這裡先擋。
if $PUBLISH_GH; then
    if ! $DO_GIT || ! $GIT_PUSH; then
        echo "❌ 發 GitHub Release 需要先 commit 並 push（tag 要指在含 APK 的發版 commit）。只想 build 請加 --no-publish"
        exit 1
    fi
    if [ -z "$(git branch --show-current 2>/dev/null)" ]; then
        echo "❌ 目前不在任何分支上（detached HEAD），發版 commit 推不上去。請先切到要發版的分支"
        exit 1
    fi
    if ! command -v gh >/dev/null 2>&1; then
        echo "❌ gh CLI 未安裝。執行 'brew install gh && gh auth login' 後重跑，或加 --no-publish 只 build"
        exit 1
    fi
    if ! gh auth status >/dev/null 2>&1; then
        echo "❌ gh 未登入。執行 'gh auth login' 後重跑，或加 --no-publish 只 build"
        exit 1
    fi
fi

# ── 1. 取得版號資訊 ──
GRADLE_FILE="app/build.gradle.kts"

if [ -n "${POSITIONAL[0]:-}" ]; then
    VERSION_NAME="${POSITIONAL[0]}"
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

# ── 7. git add + commit + push (optional) ──
# 必須在發 Release 之前：Release 的 tag 要指向這個 commit（原因見檔頭）。
GIT_PUSHED=false
if $DO_GIT; then
    echo ""
    # Stage version bump (if any) + mov_app archive. Limited paths so we never
    # accidentally pull in unrelated working-tree changes.
    git add app/build.gradle.kts mov_app/ 2>/dev/null || true

    if git diff --cached --quiet; then
        echo "▶ 沒有 staged 變動，跳過 commit"
    else
        COMMIT_MSG="發版 v${VERSION_NAME}"
        echo "▶ git commit: $COMMIT_MSG"
        git commit -m "$COMMIT_MSG"
    fi
    # 沒有新 commit 也照樣 push：release 編譯結果通常每次相同，重跑時（例如上次 commit 後
    # 發布失敗）不會有新 commit，但發版 commit 可能還沒推上去，GitHub 就無法把 tag 建在它上面。
    if $GIT_PUSH; then
        CURRENT_BRANCH=$(git branch --show-current 2>/dev/null || echo "")
        if [ -n "$CURRENT_BRANCH" ]; then
            echo "▶ git push origin $CURRENT_BRANCH"
            git push origin "$CURRENT_BRANCH"
            GIT_PUSHED=true
        else
            echo "⚠ 偵測不到當前分支，跳過 push"
        fi
    fi
fi

# ── 8. 發 GitHub Release (給線上更新流程) ──
# 前提（commit＋push、gh 可用）已在第 0 步檢查過。
GH_RELEASE_URL=""
if $PUBLISH_GH; then
    TAG="v${VERSION_NAME}"
    TARGET_SHA=$(git rev-parse HEAD)
    if ! git cat-file -e "${TARGET_SHA}:${RELEASE_DIR}/${APK_FILENAME}" 2>/dev/null; then
        echo "❌ 發版 commit ${TARGET_SHA:0:7} 裡沒有 ${RELEASE_DIR}/${APK_FILENAME}，tag 指過去 jsDelivr 也抓不到，停止發布"
        exit 1
    fi

    # Compose release notes
    TMP_NOTES=$(mktemp)
    trap 'rm -f "$TMP_NOTES"' EXIT
    if [ -n "$NOTES_FILE" ] && [ -f "$NOTES_FILE" ]; then
        cp "$NOTES_FILE" "$TMP_NOTES"
    elif [ -n "$NOTES_INLINE" ]; then
        printf '%s\n' "$NOTES_INLINE" > "$TMP_NOTES"
    else
        # Auto: changelog from previous tag → HEAD
        # 寫成純文字：這份說明會直接顯示在 App 的更新視窗（純 Text），v3.1.15 以前的 App 不會去掉
        # markdown，`##`、`- `、連結語法都會原封不動顯示。項目間空一行，GitHub 網頁才不會併成一段。
        # 排除「發版 vX」：發版 commit 在第 7 步已經做好，此時就是 HEAD，不排除會被列成一項。
        LAST_TAG=$(git tag --sort=-v:refname | grep -v "^${TAG}$" | head -1 || true)
        {
            echo "【變更紀錄】"
            if [ -n "$LAST_TAG" ]; then
                git log "${LAST_TAG}..HEAD" --invert-grep --grep='^發版 v' --pretty=format:'%n・%s' \
                    || printf '\n・v%s' "${VERSION_NAME}"
            else
                git log -10 --invert-grep --grep='^發版 v' --pretty=format:'%n・%s'
            fi
            echo ""
        } > "$TMP_NOTES"
    fi

    echo ""
    echo "▶ 發 GitHub Release: ${TAG} → ${GH_REPO}（tag 指向 ${TARGET_SHA:0:7}）"
    if gh release view "$TAG" --repo "$GH_REPO" >/dev/null 2>&1; then
        echo "  release 已存在 → 上傳/覆蓋 asset (--clobber)"
        gh release upload "$TAG" "$RELEASE_DIR/$APK_FILENAME" --repo "$GH_REPO" --clobber
    else
        gh release create "$TAG" "$RELEASE_DIR/$APK_FILENAME" \
            --repo "$GH_REPO" \
            --target "$TARGET_SHA" \
            --title "v${VERSION_NAME}" \
            --notes-file "$TMP_NOTES"
    fi
    GH_RELEASE_URL="https://github.com/${GH_REPO}/releases/tag/${TAG}"
    echo "  → $GH_RELEASE_URL"
fi

# ── 9. 完成 ──
echo ""
echo "══════════════════════════════════════"
echo "  ✅ Release 完成!"
echo ""
echo "  版本:  v${VERSION_NAME} (${VERSION_CODE})"
echo "  APK:   $RELEASE_DIR/$APK_FILENAME"
echo "  大小:  $APK_SIZE"
echo "  MD5:   $APK_MD5"
echo "  JSON:  $VERSION_JSON"
[ -n "$GH_RELEASE_URL" ] && echo "  GH:    $GH_RELEASE_URL"
$GIT_PUSHED && echo "  Git:   已 push 到 origin/$(git branch --show-current)"
echo "══════════════════════════════════════"
