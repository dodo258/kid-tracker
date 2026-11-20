#!/bin/bash
# 删除超过保留天数的定位和事件（默认 365 天），每台设备的最后一个位置永远保留。
# 只能在 Traccar 停着时跑（H2 是单进程文件数据库）：由 traccar-backup.sh 在备份打包之后、启动之前调用。
# 用法：traccar-prune.sh [数据目录，默认 /opt/traccar/data] [保留天数，默认 365]
set -u
DATA=${1:-/opt/traccar/data}
DAYS=${2:-365}
LOG=/var/log/traccar-prune.log
JAR=$(ls /opt/traccar/lib/h2-[0-9]*.jar | head -1)
SQL="DELETE FROM tc_events WHERE eventtime < DATEADD('DAY', -$DAYS, CURRENT_TIMESTAMP);
DELETE FROM tc_positions WHERE fixtime < DATEADD('DAY', -$DAYS, CURRENT_TIMESTAMP)
  AND id NOT IN (SELECT positionid FROM tc_devices WHERE positionid IS NOT NULL);"
if systemctl is-active -q traccar && [ "$DATA" = /opt/traccar/data ]; then
  echo "$(date '+%F %T') traccar 在运行，跳过" >> "$LOG"; exit 1
fi
OUT=$(/opt/traccar/jre/bin/java -cp "$JAR" org.h2.tools.Shell -url "jdbc:h2:$DATA/database" -user sa -password "" -sql "$SQL" 2>&1)
RC=$?
echo "$(date '+%F %T') 保留 $DAYS 天 rc=$RC $(echo "$OUT" | tr '\n' ' ' | cut -c1-300)" >> "$LOG"
exit $RC
