#!/usr/bin/env bash
# Dùng: ./deploy.sh <repo-url> [nhánh=main] [file-zip=StreamComposer.zip]
# - Nếu chỉ có <zip>.b64 (base64) thì giải mã ra zip trước
# - Giải nén, xoá zip, commit và push
# - Token: export GITHUB_TOKEN=... (nếu repo https cần xác thực)
set -euo pipefail
REPO="${1:?Thiếu repo-url, ví dụ https://github.com/user/StreamComposer.git}"
BRANCH="${2:-main}"
ZIP="${3:-StreamComposer.zip}"

[ -f "$ZIP" ] || { [ -f "$ZIP.b64" ] && base64 -d "$ZIP.b64" > "$ZIP" && rm -f "$ZIP.b64"; }
[ -f "$ZIP" ] || { echo "Không thấy $ZIP"; exit 1; }

unzip -oq "$ZIP"
rm -f "$ZIP"
cd StreamComposer

[ -d .git ] || git init -q
git checkout -q -B "$BRANCH"
git add -A
git -c user.name="${GIT_NAME:-vinny}" -c user.email="${GIT_EMAIL:-vinny@users.noreply.github.com}" commit -qm "Stream Composer: initial import" || true

URL="$REPO"
[ -n "${GITHUB_TOKEN:-}" ] && URL="${REPO/https:\/\//https://x-access-token:${GITHUB_TOKEN}@}"
git remote remove origin 2>/dev/null || true
git remote add origin "$URL"
git push -u origin "$BRANCH"
echo "Đã push lên $REPO ($BRANCH)"
