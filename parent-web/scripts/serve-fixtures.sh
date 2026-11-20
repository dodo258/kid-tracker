# 由 shots.sh、gestures.sh 用 `.` 引入（当前目录已是 parent-web）：检查 Chrome，用 fixtures/ 假数据起 dev server，
# 等它能打开后设好 $URL；脚本退出时关掉 server。没有 Chrome 退出 125（这台机器跑不了）。
CHROME="${CHROME:-/Applications/Google Chrome.app/Contents/MacOS/Google Chrome}"
[ -x "$CHROME" ] || { echo "没有 Chrome：$CHROME"; exit 125; }
mkdir -p build
[ -d node_modules ] || npm ci --no-audit --no-fund --loglevel=error
PORT="${SHOTS_PORT:-$((5200 + $$ % 300))}"
URL="http://127.0.0.1:$PORT/m/"

npx vite --mode fixtures --port "$PORT" --strictPort >"build/fixtures-server-$PORT.log" 2>&1 &
SERVER=$!
trap 'kill $SERVER 2>/dev/null' EXIT
i=0
until curl -sf "$URL" >/dev/null; do
  i=$((i + 1)); [ $i -gt 60 ] && { echo "dev server 没起来"; cat "build/fixtures-server-$PORT.log"; exit 1; }
  sleep 0.5
done
