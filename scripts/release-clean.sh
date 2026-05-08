#!/usr/bin/env bash
# release-clean.sh — wrap scripts/release.sh with sanitized release notes
#
# Why: GitHub releases are public. We don't want commit messages mentioning
# adult-content keywords ("18+" / "成人" / "倫理" / "露骨" / "adult") to leak
# into release notes. This script generates notes from git log, swaps sensitive
# words for neutral synonyms, then hands them to release.sh via --notes-file.
#
# 用法:
#   ./scripts/release-clean.sh                # 自動產 notes 並 release
#   ./scripts/release-clean.sh --preview       # 只印 notes 預覽，不 release
#   ./scripts/release-clean.sh --no-publish    # 透傳給 release.sh
#   ./scripts/release-clean.sh -- "額外行 1" "額外行 2"
#         -- 之後的字串會以 bullet 形式追加在自動 notes 後面
#
# 替換規則（按長詞→短詞順序避免短詞先吃掉長詞）:
#   劇情倫理      → 限制級劇情
#   18+ / 18 +   → 分類拓展
#   露骨          → 高階
#   倫理片        → 特殊分類
#   倫理          → 特殊分類
#   成人          → 特殊內容
#   adult/Adult   → category
#
# 若需新增規則，編輯下方的 SED_CMD。

set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

PREVIEW_ONLY=false
EXTRA_ARGS=()
EXTRA_BULLETS=()
PARSING_BULLETS=false
for arg in "$@"; do
  if $PARSING_BULLETS; then
    EXTRA_BULLETS+=("$arg")
    continue
  fi
  case "$arg" in
    --preview) PREVIEW_ONLY=true ;;
    --) PARSING_BULLETS=true ;;
    *) EXTRA_ARGS+=("$arg") ;;
  esac
done

# Pick previous tag — if HEAD itself has a tag we want the one before that
ALL_TAGS=$(git tag --sort=-v:refname | head -5)
LAST_TAG=""
HEAD_SHA=$(git rev-parse HEAD)
for tag in $ALL_TAGS; do
  TAG_SHA=$(git rev-list -n 1 "$tag" 2>/dev/null || true)
  if [ "$TAG_SHA" != "$HEAD_SHA" ]; then
    LAST_TAG="$tag"
    break
  fi
done

TMP_NOTES=$(mktemp)
trap "rm -f $TMP_NOTES" EXIT

# Reads commit subjects since LAST_TAG → sanitizes → emits notes
{
  echo "## 變更紀錄"
  echo ""
  if [ -n "$LAST_TAG" ]; then
    git log --pretty=format:'- %s' "${LAST_TAG}..HEAD"
  else
    git log --pretty=format:'- %s' -10
  fi
  echo ""
} | sed -E \
    -e 's/劇情倫理/限制級劇情/g' \
    -e 's/18\+/分類拓展/g' \
    -e 's/18 \+/分類拓展/g' \
    -e 's/露骨/高階/g' \
    -e 's/倫理片/特殊分類/g' \
    -e 's/倫理/特殊分類/g' \
    -e 's/成人/特殊內容/g' \
    -e 's/[Aa]dult/category/g' \
  > "$TMP_NOTES"

# Append user-provided extra bullets (after `--`)
if [ ${#EXTRA_BULLETS[@]} -gt 0 ]; then
  for bullet in "${EXTRA_BULLETS[@]}"; do
    echo "- $bullet" >> "$TMP_NOTES"
  done
fi

# Footer with download link
{
  echo ""
  echo "📥 從本頁附件下載 APK 安裝即可（同 keystore，可直接覆蓋升級）"
} >> "$TMP_NOTES"

# Show what we're about to publish
echo "════════════ Release notes 預覽（已過濾敏感詞）════════════"
cat "$TMP_NOTES"
echo "═════════════════════════════════════════════════════════"

# Lint: sanity-check no sensitive words leaked through
LEAKED=$(grep -E "18\+|成人|倫理|露骨|[Aa]dult" "$TMP_NOTES" || true)
if [ -n "$LEAKED" ]; then
  echo ""
  echo "⚠️  WARNING: 過濾後仍偵測到敏感詞，請檢查 SED_CMD 規則："
  echo "$LEAKED" | sed 's|^|    |'
  echo ""
  if [ "$PREVIEW_ONLY" = false ]; then
    read -p "繼續 release? (y/N) " REPLY
    case "$REPLY" in [Yy]*) ;; *) echo "取消"; exit 0 ;; esac
  fi
fi

if [ "$PREVIEW_ONLY" = true ]; then
  echo ""
  echo "(--preview 模式，未執行 release)"
  exit 0
fi

echo ""
echo "▶ 呼叫 scripts/release.sh --notes-file=$TMP_NOTES ${EXTRA_ARGS[*]}"
"$SCRIPT_DIR/release.sh" --notes-file="$TMP_NOTES" "${EXTRA_ARGS[@]}"
