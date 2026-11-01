#!/usr/bin/env bash
#
# 发布「自研 FFmpeg 测试版」的应用内更新（仓库 gua123/mediagate-ffmpeg，公开）。
#
# 流程：构建 ffmpegTest APK → 建 GitHub Release → 传 APK → 写 update.json（仓库根）→ 提交并推送。
# App 端匿名读 raw 清单 + Release 资产，不需要任何 token。
#
# 用法：
#   bash scripts/publish-update.sh                 # 构建并发布
#   bash scripts/publish-update.sh --skip-build    # 复用已有 ffmpegTest APK
#   NOTES="更新说明" bash scripts/publish-update.sh
#
# 版本号（独立版本线，发版前先改）：
#   app/build.gradle.kts 里的 ffmpegVersionCode / ffmpegVersionName
#
# 凭据：环境变量 GH_TOKEN，或本机 ~/.git-credentials（git credential store）。
# 网络：本机 github.com 直连不通；API 走 /etc/profile 里的 https_proxy，git 已全局配 http.proxy。
# 安全：脚本只用 token 调 GitHub API，不打印 token，也不把它写进任何文件。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

OWNER="gua123"
REPO="mediagate-ffmpeg"
BUILD_TASK=":app:assembleFfmpegTest"
APK="app/build/outputs/apk/ffmpegTest/app-ffmpegTest.apk"
SKIP_BUILD=0
[ "${1:-}" = "--skip-build" ] && SKIP_BUILD=1

VERSION_CODE="$(grep -m1 '^val ffmpegVersionCode = ' app/build.gradle.kts | sed 's/[^0-9]//g')"
VERSION_NAME="$(grep -m1 '^val ffmpegVersionName = ' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')"
if [ -z "${VERSION_CODE:-}" ] || [ -z "${VERSION_NAME:-}" ]; then
  echo "！！ 读不到 ffmpegVersionCode / ffmpegVersionName（见 app/build.gradle.kts）" >&2
  exit 1
fi
TAG="v${VERSION_NAME}"
ASSET="mediagate-${VERSION_NAME}.apk"
NOTES_FILE="docs/release-notes-${VERSION_NAME}.md"

echo "== 发布 mediagate-ffmpeg ${VERSION_NAME}（versionCode ${VERSION_CODE}）到 ${OWNER}/${REPO} =="

# 代理：直连 github.com 常常超时（本机实测），有代理就带上
PROXY="${HTTPS_PROXY:-${https_proxy:-}}"
if [ -z "$PROXY" ] && [ -r /etc/profile ]; then
  PROXY="$(grep -m1 'https_proxy=' /etc/profile | sed 's/.*="\(.*\)".*/\1/' || true)"
fi
CURL=(curl -sS)
[ -n "$PROXY" ] && CURL+=(-x "$PROXY")

# token：环境变量优先，其次本机 git 凭据（读出来只放内存）
TOKEN="${GH_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -r "$HOME/.git-credentials" ]; then
  TOKEN="$(sed -E 's#https://([^:]+):([^@]+)@github.com#\2#' "$HOME/.git-credentials" | head -1)"
fi
if [ -z "$TOKEN" ]; then
  echo "！！ 没有可用的 GitHub token：请设 GH_TOKEN 或先让 git 记住凭据" >&2
  exit 1
fi

api() {
  "${CURL[@]}" -H "Authorization: Bearer $TOKEN" -H "User-Agent: mediagate-ffmpeg-publish" \
    -H "Accept: application/vnd.github+json" "$@"
}

# ---------------------------------------------------------------- 1. 构建
if [ "$SKIP_BUILD" = "0" ]; then
  echo "-- 构建 ffmpegTest 包"
  # shellcheck disable=SC1091
  source scripts/env.sh >/dev/null 2>&1
  ./gradlew "$BUILD_TASK"
fi
if [ ! -f "$APK" ]; then
  echo "！！ 找不到 $APK（先跑 ./gradlew $BUILD_TASK）" >&2
  exit 1
fi

SIZE="$(stat -c %s "$APK")"
SHA="$(sha256sum "$APK" | awk '{print $1}')"
echo "-- APK：$APK（$SIZE 字节，sha256 $SHA）"

# ---------------------------------------------------------------- 2. 建/取 Release
NOTES="${NOTES:-}"
if [ -z "$NOTES" ] && [ -f "$NOTES_FILE" ]; then
  NOTES="$(cat "$NOTES_FILE")"
fi
if [ -z "$NOTES" ]; then
  NOTES="mediagate-ffmpeg ${VERSION_NAME}（versionCode ${VERSION_CODE}）"
fi

PAYLOAD="$(python3 - "$TAG" "$VERSION_NAME" "$NOTES" <<'PY'
import json, sys
tag, name, notes = sys.argv[1], sys.argv[2], sys.argv[3]
print(json.dumps({"tag_name": tag, "name": name, "body": notes, "draft": False, "prerelease": False}))
PY
)"

echo "-- 建 Release $TAG"
RESP="$(api -X POST "https://api.github.com/repos/$OWNER/$REPO/releases" -d "$PAYLOAD" -w '\n%{http_code}')"
CODE="$(printf '%s' "$RESP" | tail -1)"
BODY="$(printf '%s' "$RESP" | sed '$d')"
if [ "$CODE" = "422" ]; then
  echo "-- 该 tag 的 Release 已存在，改为复用它"
  RESP="$(api "https://api.github.com/repos/$OWNER/$REPO/releases/tags/$TAG" -w '\n%{http_code}')"
  CODE="$(printf '%s' "$RESP" | tail -1)"
  BODY="$(printf '%s' "$RESP" | sed '$d')"
fi
if [ "$CODE" != "200" ] && [ "$CODE" != "201" ]; then
  echo "！！ 建 Release 失败（HTTP $CODE）：$BODY" >&2
  exit 1
fi

RELEASE_ID="$(printf '%s' "$BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin)["id"])')"
echo "-- Release id=$RELEASE_ID"

# ---------------------------------------------------------------- 3. 传资产（同名先删再传，保证可重跑）
api "https://api.github.com/repos/$OWNER/$REPO/releases/$RELEASE_ID/assets" -o /tmp/publish-assets.json
EXISTING="$(python3 -c '
import json, sys
name, path = sys.argv[1], sys.argv[2]
for asset in json.load(open(path)):
    if asset.get("name") == name:
        print(asset["id"])
        break
' "$ASSET" /tmp/publish-assets.json)"
if [ -n "$EXISTING" ]; then
  echo "-- 删除同名旧资产 id=$EXISTING"
  api -X DELETE "https://api.github.com/repos/$OWNER/$REPO/releases/assets/$EXISTING" -o /dev/null
fi

# 大文件上传强制 HTTP/1.1 并关掉 Expect: 100-continue（走代理时 HTTP/2 的 POST 会卡死）；
# uploads.github.com 直连通常可用，失败再退回代理重试一次。
ASSET_URL="$(api "https://api.github.com/repos/$OWNER/$REPO/releases/$RELEASE_ID" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)["upload_url"].split("{")[0])')"
echo "-- 上传 $ASSET → $ASSET_URL"
upload() {
  curl -sS --http1.1 -X POST \
    -H "Authorization: Bearer $TOKEN" -H "User-Agent: mediagate-ffmpeg-publish" \
    -H "Content-Type: application/vnd.android.package-archive" \
    -H "Expect:" \
    --data-binary @"$APK" \
    "$ASSET_URL?name=$ASSET" \
    -o /tmp/publish-asset.json -w '%{http_code}'
}
UP_CODE="$(upload || true)"
if [ "$UP_CODE" != "201" ]; then
  echo "-- 直连上传返回 $UP_CODE，改用代理重试"
  upload() {
    curl -sS --http1.1 -x "$PROXY" -X POST \
      -H "Authorization: Bearer $TOKEN" -H "User-Agent: mediagate-ffmpeg-publish" \
      -H "Content-Type: application/vnd.android.package-archive" \
      -H "Expect:" \
      --data-binary @"$APK" \
      "$ASSET_URL?name=$ASSET" \
      -o /tmp/publish-asset.json -w '%{http_code}'
  }
  UP_CODE="$(upload || true)"
fi
if [ "$UP_CODE" != "201" ]; then
  echo "！！ 上传失败（HTTP $UP_CODE）" >&2
  exit 1
fi
python3 - <<'PY'
import json
d = json.load(open('/tmp/publish-asset.json'))
print("  资产：", d.get("name"), d.get("size"), "字节")
print("  直链：", d.get("browser_download_url"))
PY

APK_URL="https://github.com/$OWNER/$REPO/releases/download/$TAG/$ASSET"

# ---------------------------------------------------------------- 4. 写 update.json（提交进仓库，App 从 raw 读）
echo "-- 写 update.json"
python3 - "$VERSION_CODE" "$VERSION_NAME" "$APK_URL" "$SIZE" "$SHA" "$NOTES" <<'PY'
import json, sys
code, name, url, size, sha, notes = sys.argv[1], sys.argv[2], sys.argv[3], int(sys.argv[4]), sys.argv[5], sys.argv[6]
manifest = {
    "versionCode": int(code),
    "versionName": name,
    "apkUrl": url,
    "sizeBytes": size,
    "sha256": sha,
    "notes": notes.strip(),
}
with open("update.json", "w", encoding="utf-8") as f:
    json.dump(manifest, f, ensure_ascii=False, indent=2)
    f.write("\n")
print(json.dumps(manifest, ensure_ascii=False)[:200])
PY

git add update.json
if git diff --cached --quiet; then
  echo "-- update.json 无变化，跳过提交"
else
  git -c user.name="mediagate-ffmpeg-publish" -c user.email="publish@local" commit -q -m "release: ${VERSION_NAME}（versionCode ${VERSION_CODE}）"
fi
echo "-- 推送"
GIT_TERMINAL_PROMPT=0 git push origin HEAD:main 2>&1 | tail -2

echo
echo "== 完成 =="
echo "  版本：${VERSION_NAME}（${VERSION_CODE}）"
echo "  资产：$APK_URL"
echo "  清单：https://raw.githubusercontent.com/$OWNER/$REPO/main/update.json"
echo "  发布页：https://github.com/$OWNER/$REPO/releases"
