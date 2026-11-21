#!/usr/bin/env bash
# 从 OpenList 官方发布下载 32 位安卓服务器程序,核对 sha256,放到 app/core/armeabi-v7a/libopenlist.so。
# 用法:scripts/fetch-core.sh [版本,默认 latest]
set -euo pipefail
cd "$(dirname "$0")/.."
TAG="${1:-latest}"
API="repos/OpenListTeam/OpenList/releases/${TAG/#latest/latest}"
[ "$TAG" != latest ] && API="repos/OpenListTeam/OpenList/releases/tags/$TAG"
NAME=openlist-android-arm.tar.gz
URL=$(gh api "$API" --jq ".assets[] | select(.name==\"$NAME\") | .browser_download_url")
WANT=$(gh api "$API" --jq ".assets[] | select(.name==\"$NAME\") | .digest" | sed 's/^sha256://')
TMP=$(mktemp -d)
curl -fL --retry 3 -o "$TMP/$NAME" "$URL"
GOT=$(shasum -a 256 "$TMP/$NAME" | cut -d' ' -f1)
[ "$GOT" = "$WANT" ] || { echo "校验值不对: $GOT != $WANT" >&2; exit 1; }
tar -xzf "$TMP/$NAME" -C "$TMP"
mkdir -p app/core/armeabi-v7a
mv "$TMP/openlist" app/core/armeabi-v7a/libopenlist.so
echo "OK: $(file app/core/armeabi-v7a/libopenlist.so | cut -d, -f1-3)"
