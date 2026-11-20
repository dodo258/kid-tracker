#!/bin/bash
# Traccar 健康检查（root crontab 每 5 分钟）：网页后台接口连续两次（隔 20 秒）没响应就重启 traccar。
# 也管「停着没起来」（备份被中断等）：不在备份窗口里就启动。进程崩溃由 systemd Restart=always 管。结果写 /var/log/traccar-health.log。
LOG=/var/log/traccar-health.log
ok() { curl -s -o /dev/null -m 10 -w '%{http_code}' http://127.0.0.1:8082/api/server | grep -q '^200$'; }
LOCK=/run/traccar-backup.lock
if ! systemctl is-active -q traccar; then
  # 备份停机窗口内（标记在、不到 20 分钟）不插手；否则是意外停着（备份脚本被中断、启动失败等），拉起来
  if [ -e "$LOCK" ] && [ -n "$(find "$LOCK" -mmin -20)" ]; then exit 0; fi
  echo "$(date '+%F %T') traccar 停着且不在备份中，启动" >> "$LOG"
  systemctl start traccar
  exit 0
fi
ok && exit 0
sleep 20
ok && exit 0
echo "$(date '+%F %T') 接口两次没响应，重启 traccar" >> "$LOG"
systemctl restart traccar
