#!/bin/bash
# 由 setup-telegram.sh 传到服务器执行；从 stdin 读 Token、ID、中转地址三行。
set -euo pipefail
read -r TOKEN; read -r CHAT_ID; read -r BASE_URL
CONF=/opt/traccar/conf/traccar.xml
cp -a "$CONF" "$CONF.bak-$(date +%Y%m%d-%H%M%S)"
python3 - "$CONF" "$TOKEN" "$CHAT_ID" "$BASE_URL" <<'PY'
import re, sys
path, token, chat, base = sys.argv[1:5]
s = open(path, encoding="utf-8").read()
s = re.sub(r'\s*<entry key="notificator\.(types|telegram\.[^"]+)">[^<]*</entry>', '', s)
s = re.sub(r'\s*<!-- Telegram 通知 -->', '', s)
block = (
    '\n    <!-- Telegram 通知 -->\n'
    '    <entry key="notificator.types">web,telegram</entry>\n'
    f'    <entry key="notificator.telegram.key">{token}</entry>\n'
    f'    <entry key="notificator.telegram.chatId">{chat}</entry>\n'
    f'    <entry key="notificator.telegram.base.url">{base}</entry>\n'
)
s = s.replace('</properties>', block + '</properties>')
open(path, 'w', encoding="utf-8").write(s)
PY
chmod 600 "$CONF"
# 先直接发一条测试消息，确认 Token、ID、中转都对
RESP=$(curl -s -m 20 -X POST -H 'content-type: application/json' \
  -d "{\"chat_id\":\"$CHAT_ID\",\"text\":\"定位上报服务器：Telegram 通知已接通 ✅\"}" \
  "${BASE_URL}${TOKEN}/sendMessage")
case "$RESP" in
  *'"ok":true'*) echo "测试消息已发出，去 Telegram 看看。" ;;
  *) echo "测试消息失败：$(echo "$RESP" | sed -E 's/[0-9]+:[A-Za-z0-9_-]{20,}/<token>/g')" ;;
esac
systemctl restart traccar
for i in $(seq 1 40); do ss -tln | grep -q ':8082 ' && break; sleep 2; done
systemctl is-active traccar >/dev/null && echo "Traccar 已重启，配置生效。"
