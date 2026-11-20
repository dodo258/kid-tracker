#!/bin/bash
# 上线家长极简页：每次都先装依赖、检查、测试、重新打包，再按「新目录 + 改名」传上服务器（旧版留 /opt/parent-web.bak-<时间>），
# 最后核对线上引用的 JS 文件名和这次打包的一致。用法：bash server/deploy-parent-web.sh
set -euo pipefail
cd "$(dirname "$0")/../parent-web"
KEYS=(-i "$HOME/.ssh/tracker_ed25519" -o IdentitiesOnly=yes -o BatchMode=yes)
HOST=root@203.0.113.10
npm ci --no-audit --no-fund --loglevel=error >/dev/null
npm run -s typecheck
npm run -s lint
npm test --silent >/dev/null
rm -rf dist && npm run -s build >/dev/null
LOCAL_JS=$(grep -o 'assets/index-[^"]*\.js' dist/index.html)
ssh "${KEYS[@]}" $HOST 'test ! -e /opt/parent-web.new'
scp -q "${KEYS[@]}" -r dist $HOST:/opt/parent-web.new
ssh "${KEYS[@]}" $HOST 'set -e
  chmod -R a+rX /opt/parent-web.new
  B=/opt/parent-web.bak-$(date +%Y%m%d-%H%M%S)
  mv /opt/parent-web "$B"; mv /opt/parent-web.new /opt/parent-web
  echo "旧版留在 $B"
  ls -dt /opt/parent-web.bak-* | tail -n +11 | xargs -r rm -rf   # 只留最近 10 个旧版'
LIVE_JS=$(curl -s https://gps.example.com/m/ | grep -o 'assets/index-[^"]*\.js')
[ "$LIVE_JS" = "$LOCAL_JS" ] || { echo "线上 $LIVE_JS 和本地 $LOCAL_JS 不一致"; exit 1; }
echo "已上线 $LIVE_JS（$(git -C .. rev-parse --short HEAD)）"
