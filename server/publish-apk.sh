#!/bin/bash
# 发布一个新版 APK 给已安装的 App 检查更新：读 APK 的 versionCode/versionName、算 sha256，
# 生成更新文件（latest.json / latest-owner.json），和 APK 一起传到服务器 /opt/app-release/（Caddy 提供 https://gps.example.com/app/）。
# 旧版本 APK 留在服务器上（回退：把更新文件改回旧的 versionCode 和文件名）。
# 两个更新通道：标准版（国产手机）latest.json + dingwei-<版本>.apk；
# 三星专用版加 --owner：latest-owner.json + dingwei-owner-<版本>.apk（设备所有者手机每天查它、静默安装）。
# 用法：bash server/publish-apk.sh [--owner] [--check-only] [APK 路径] "这次更新的一句话说明"
#   --check-only 只做本地检查（正式签名、包名、非调试包、包和通道对得上），不连服务器；交机脚本也用它验包
#   APK 默认 build/app/outputs/flutter-apk/app-release.apk，--owner 时默认 app-owner-release.apk（flutter build apk --release --flavor owner）
set -euo pipefail
cd "$(dirname "$0")/.."
CHANNEL=""
CHECK_ONLY=0
while [ $# -gt 0 ]; do
  case "$1" in
    --owner) CHANNEL="-owner"; shift ;;
    --check-only) CHECK_ONLY=1; shift ;;
    *) break ;;
  esac
done
DEFAULT_APK=build/app/outputs/flutter-apk/app-release.apk
[ -n "$CHANNEL" ] && DEFAULT_APK=build/app/outputs/flutter-apk/app-owner-release.apk
APK=${1:-$DEFAULT_APK}
NOTES=${2:-修复问题，提升稳定性}
LATEST="latest$CHANNEL.json"
HOST=root@203.0.113.10
KEYS=(-i "$HOME/.ssh/tracker_ed25519" -o IdentitiesOnly=yes -o BatchMode=yes)
PKG=com.dodo258.tracker
# 正式证书的 SHA-256（SHA-1 是 6f78a5dcd77d0f64c03040b1f48783a209d74e2c）
CERT_SHA256=5655561565667332a7ad9ca8ece4004540f3dd32f3d1a6c25b095ec6fe29e0c6
die() { echo "$*" >&2; exit 1; }
[ -f "$APK" ] || die "没有 $APK"
# BUILD_TOOLS 可指定 build-tools 目录（测试用假工具）；默认取 Android SDK 里最新的一版
BT=${BUILD_TOOLS:-$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/*/ | tail -1)}
BT=${BT%/}
# 工具输出先完整存进变量、单独看退出码，再匹配：pipefail 下 `aapt2 … | grep -q` 找到后 grep 先退出，
# aapt2 收到 SIGPIPE（141），整条管道算失败，owner 包会被当成标准包
CERTS=$("$BT/apksigner" verify --print-certs "$APK") || die "apksigner 验签没过，不发布：$APK"
# 只认签名者证书的 SHA-256 字段，且所有签名方案只有这一个证书
SIGNERS=$(sed -n 's/.*certificate SHA-256 digest: \([0-9a-f]\{64\}\)$/\1/p' <<<"$CERTS" | sort -u)
[ "$SIGNERS" = "$CERT_SHA256" ] || die "不是正式签名的 APK（签名证书 SHA-256：${SIGNERS:-读不到}），不发布"
BADGE=$("$BT/aapt2" dump badging "$APK") || die "aapt2 读不出包信息，不发布：$APK"
BADGE=$(sed -n 1p <<<"$BADGE")
PACKAGE=$(sed -n "s/^package: name='\([^']*\)'.*/\1/p" <<<"$BADGE")
[ "$PACKAGE" = "$PKG" ] || die "包名是 ${PACKAGE:-读不到}，不是 $PKG，不发布"
MANIFEST=$("$BT/aapt2" dump xmltree "$APK" --file AndroidManifest.xml) || die "aapt2 读不出清单，不发布：$APK"
if grep -qE ':(debuggable|testOnly)\(0x[0-9a-f]+\)=true' <<<"$MANIFEST"; then die "这是调试包或测试包（debuggable/testOnly），不发布"; fi
# 包和通道要对得上：owner 版的清单里有静默更新任务，标准版没有；发错了会把另一版静默装到三星机上（或反过来）
if grep -q 'OwnerUpdateJob' <<<"$MANIFEST"; then FLAVOR=owner; else FLAVOR=standard; fi
if [ -n "$CHANNEL" ] && [ "$FLAVOR" = standard ]; then die "这是标准版 APK，不能发到三星专用版通道（要 --flavor owner 打的包）"; fi
if [ -z "$CHANNEL" ] && [ "$FLAVOR" = owner ]; then die "这是三星专用版 APK，发它要加 --owner"; fi
CODE=$(sed -nE "s/.*versionCode='([0-9]+)'.*/\1/p" <<<"$BADGE")
NAME=$(sed -nE "s/.*versionName='([0-9A-Za-z._-]+)'.*/\1/p" <<<"$BADGE")
[ -n "$CODE" ] && [ -n "$NAME" ] || die "读不出 versionCode/versionName（versionName 只能是字母数字和 ._-），不发布"
if [ "$CHECK_ONLY" = 1 ]; then echo "通过：$FLAVOR 版 $NAME（$CODE），包名、正式签名、通道都对"; exit 0; fi
SHA=$(shasum -a 256 "$APK" | cut -d' ' -f1)
FILE="dingwei$CHANNEL-$NAME-$CODE.apk"
TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
python3 - "$CODE" "$NAME" "$FILE" "$NOTES" "$SHA" > "$TMP/$LATEST" <<'PY'
import json, sys
code, name, file, notes, sha = sys.argv[1:6]
print(json.dumps({"versionCode": int(code), "versionName": name, "apkUrl": file, "notes": notes, "sha256": sha}, ensure_ascii=False))
PY
CUR=$(curl -s -m 10 "https://gps.example.com/app/$LATEST" | python3 -c 'import json,sys; print(json.load(sys.stdin)["versionCode"])' 2>/dev/null || echo 0)
[ "$CODE" -gt "$CUR" ] || { echo "服务器上已经是 versionCode $CUR，这个 APK 是 $CODE，不比它新，不发布"; exit 1; }
scp -q "${KEYS[@]}" "$APK" "$HOST:/opt/app-release/$FILE.part"
scp -q "${KEYS[@]}" "$TMP/$LATEST" "$HOST:/opt/app-release/$LATEST.part"
ssh "${KEYS[@]}" "$HOST" "set -e; cd /opt/app-release
  echo '$SHA  $FILE.part' | sha256sum -c --quiet
  mv '$FILE.part' '$FILE'; chmod 644 '$FILE'
  [ -f '$LATEST' ] && cp -p '$LATEST' '$LATEST'.bak-\$(date +%Y%m%d-%H%M%S)
  mv '$LATEST.part' '$LATEST'; chmod 644 '$LATEST'; ls -la"
echo "已发布 $NAME（$CODE）：$(curl -s "https://gps.example.com/app/$LATEST")"
