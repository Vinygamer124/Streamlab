#!/usr/bin/env bash
# Dùng (trong Codespace, ở bất kỳ thư mục nào của repo):  bash deploy.sh [nhánh=main]
#  1) git pull   2) xoá mọi file ngoài .git rồi giải nén zip MỚI NHẤT ở root repo ngay tại root   3) xoá zip
#  4) biên dịch + chạy unit test + build APK debug   5) CHỈ KHI OK mới commit + push lên nhánh
# Biến tuỳ chọn: MSG="nội dung commit"  GIT_NAME / GIT_EMAIL (nếu máy chưa cấu hình git user)
set -euo pipefail

# bước xoá sẽ xoá luôn file script này → chạy bản sao trong /tmp
if [ -z "${SL_DEPLOY_COPY:-}" ] && [ -f "$0" ]; then
  cp "$0" /tmp/streamlab_deploy.sh
  SL_DEPLOY_COPY=1 exec bash /tmp/streamlab_deploy.sh "$@"
fi

BRANCH="${1:-main}"
cd "$(git rev-parse --show-toplevel)"
[ "$(pwd)" != "$HOME" ] && [ "$(pwd)" != "/" ] || { echo "✗ Không đứng trong thư mục repo"; exit 1; }
BK="$HOME/streamlab-backup"; mkdir -p "$BK"

echo "▶ 1/6 git pull ($BRANCH)"
git checkout -q "$BRANCH"
git pull --rebase --autostash origin "$BRANCH"

echo "▶ 2/6 tìm file zip mới nhất ở root"
ZIP="$(ls -t ./*.zip 2>/dev/null | head -1 || true)"
[ -n "$ZIP" ] || { echo "✗ Không thấy file .zip ở root repo (đã upload lên GitHub và pull chưa?)"; exit 1; }
echo "   dùng: $ZIP"
cp -f "$ZIP" "$BK/$(basename "$ZIP")"
TMP="$(mktemp -d)"
unzip -q "$ZIP" -d "$TMP"
# zip có thể bọc trong 1 thư mục (vd Streamlab-main/) → lấy phần ruột
if [ "$(ls -A "$TMP" | wc -l)" = 1 ] && [ -d "$TMP/$(ls -A "$TMP")" ]; then SRC="$TMP/$(ls -A "$TMP")"; else SRC="$TMP"; fi
[ -f "$SRC/settings.gradle.kts" ] || { echo "✗ Zip không phải dự án Streamlab (thiếu settings.gradle.kts) – chưa xoá gì"; exit 1; }

echo "▶ 3/6 xoá file ngoài zip, giải nén tại root, xoá zip"
find . -mindepth 1 -maxdepth 1 ! -name .git -exec rm -rf {} +
cp -a "$SRC"/. .
rm -rf "$TMP"
[ ! -e "$ZIP" ] || rm -f "$ZIP"

echo "▶ 4/6 chuẩn bị JDK 17+ / Android SDK / Gradle 8.9 (lần đầu mất vài phút, các lần sau dùng lại)"
if ! java -version 2>&1 | head -1 | grep -Eq '"(17|18|19|20|21|22)\.'; then
  sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-17-jdk-headless
  export JAVA_HOME="$(ls -d /usr/lib/jvm/java-17-openjdk-* | head -1)"; export PATH="$JAVA_HOME/bin:$PATH"
fi
export ANDROID_HOME="${ANDROID_HOME:-$HOME/android-sdk}"; export ANDROID_SDK_ROOT="$ANDROID_HOME"
SDKM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKM" ]; then
  mkdir -p "$ANDROID_HOME/cmdline-tools"
  curl -fsSL -o /tmp/cmdtools.zip https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
  rm -rf "$ANDROID_HOME/cmdline-tools/latest" "$ANDROID_HOME/cmdline-tools/cmdline-tools"
  unzip -q /tmp/cmdtools.zip -d "$ANDROID_HOME/cmdline-tools"
  mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest"
fi
if [ ! -d "$ANDROID_HOME/platforms/android-35" ] || [ ! -d "$ANDROID_HOME/build-tools/35.0.0" ]; then
  yes | "$SDKM" --licenses >/dev/null 2>&1 || true
  "$SDKM" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
fi
echo "sdk.dir=$ANDROID_HOME" > local.properties   # đã nằm trong .gitignore
G="$HOME/gradle-8.9/bin/gradle"
if [ ! -x "$G" ]; then
  curl -fsSL -o /tmp/gradle-8.9.zip https://services.gradle.org/distributions/gradle-8.9-bin.zip
  unzip -q /tmp/gradle-8.9.zip -d "$HOME"
fi

echo "▶ 5/6 chạy thử: biên dịch + unit test + build APK debug"
if ! "$G" --console=plain --no-daemon :app:testDebugUnitTest :app:assembleDebug; then
  echo
  echo "✗ CÓ LỖI → KHÔNG push. Code mới đang ở root (chưa commit); zip gốc lưu ở $BK"
  echo "  Gửi log lỗi phía trên cho Claude để sửa, hoặc hoàn tác: git checkout -- . && git clean -fd"
  exit 1
fi

echo "▶ 6/6 commit + push lên $BRANCH"
git add -A
if git diff --cached --quiet; then
  echo "Không có thay đổi nào để commit."
else
  ID=()
  [ -n "$(git config user.name || true)" ]  || ID+=(-c "user.name=${GIT_NAME:-vinny}")
  [ -n "$(git config user.email || true)" ] || ID+=(-c "user.email=${GIT_EMAIL:-vinny@users.noreply.github.com}")
  git "${ID[@]}" commit -qm "${MSG:-Streamlab: xuất theo phần + tự thử lại khi encoder AAC lỗi, icon mới}"
fi
git push origin "$BRANCH"
echo "✔ Đã push lên $BRANCH. GitHub Actions sẽ build APK."
