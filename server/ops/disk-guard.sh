#!/bin/bash
# 每天 05:00（root crontab）：清旧日志，硬盘用到 80% 以上就发 Telegram 提醒（经 Cloudflare 中转，同 Traccar 通知）。
# 不删定位历史和备份（备份由 traccar-backup.sh 保留 14 天）。结果写 /var/log/disk-guard.log。
set -u
LOG=/var/log/disk-guard.log
CONF=/opt/traccar/conf/traccar.xml
LIMIT=80

# Traccar 日志每天一个新文件，旧的保留 30 天
find /opt/traccar/logs -name 'tracker-server.log.*' -mtime +30 -delete
# 本脚本和健康检查自己的日志超过 1MB 就只留最后 1000 行
for f in /var/log/disk-guard.log /var/log/traccar-health.log; do
  [ -f "$f" ] && [ "$(stat -c %s "$f")" -gt 1048576 ] && tail -n 1000 "$f" > "$f.tmp" && mv "$f.tmp" "$f"
done

USED=$(df --output=pcent / | tail -1 | tr -dc '0-9')
DATA=$(du -sh /opt/traccar/data 2>/dev/null | cut -f1)
echo "$(date '+%F %T') 系统盘 ${USED}%，Traccar 数据 ${DATA}" >> "$LOG"
[ "$USED" -lt "$LIMIT" ] && exit 0

entry() { sed -n "s|.*<entry key=\"$1\">\([^<]*\)</entry>.*|\1|p" "$CONF" | head -1; }
TOKEN=$(entry notificator.telegram.key); CHAT=$(entry notificator.telegram.chatId); BASE=$(entry notificator.telegram.base.url)
[ -n "$TOKEN" ] && [ -n "$CHAT" ] && [ -n "$BASE" ] || { echo "$(date '+%F %T') 读不到 Telegram 配置，没发提醒" >> "$LOG"; exit 0; }
TEXT="💽 定位服务器：硬盘已用 ${USED}%（Traccar 数据 ${DATA}）。请联系管理员清理。"
curl -s -m 20 -o /dev/null -X POST -H 'content-type: application/json' \
  -d "{\"chat_id\":\"$CHAT\",\"text\":\"$TEXT\"}" "${BASE}${TOKEN}/sendMessage" \
  && echo "$(date '+%F %T') 已发提醒" >> "$LOG"
