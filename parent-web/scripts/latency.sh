#!/bin/sh
# 推送延迟：本机测试用的 Traccar 收到一个点 → WebSocket → 页面状态卡更新，要在 3 秒内（方案 2026-11-16-three-states）。
# 只连本机 Traccar，不要指向生产服务器。用法：
#   TRACCAR_URL=http://127.0.0.1:8082 OSMAND_URL=http://127.0.0.1:5055 TRACCAR_TOKEN=<令牌> DEVICE=<uniqueId> npm run latency
# 令牌在本机 Traccar 网页「设置 → 偏好设置 → 令牌」生成；DEVICE 要是这个账号能看的设备。退出码 125 = 没配好、跑不了。
set -eu
: "${TRACCAR_URL:?要 TRACCAR_URL}" "${OSMAND_URL:?要 OSMAND_URL}" "${TRACCAR_TOKEN:?要 TRACCAR_TOKEN}" "${DEVICE:?要 DEVICE}"
case "$TRACCAR_URL$OSMAND_URL" in *127.0.0.1*|*localhost*) ;; *) echo "只连本机 Traccar"; exit 125 ;; esac
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
[ -x "$CHROME" ] || { echo "没有 Chrome：$CHROME"; exit 125; }
cd "$(dirname "$0")/.."
mkdir -p build
PORT="${LATENCY_PORT:-$((5500 + $$ % 300))}"
URL="http://127.0.0.1:$PORT/m/"
TRACCAR_URL="$TRACCAR_URL" npx vite --port "$PORT" --strictPort >"build/latency-server-$PORT.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
i=0
until curl -sf "$URL" >/dev/null; do
  i=$((i + 1)); [ $i -gt 60 ] && { echo "dev server 没起来"; cat "build/latency-server-$PORT.log"; exit 1; }
  sleep 0.5
done
node scripts/latency.mjs "$URL"
