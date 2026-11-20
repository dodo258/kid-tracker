#!/bin/bash
# 每天备份 Traccar（H2 数据库 + 配置）：停服务十几秒保证文件一致，手机端离线缓存会补传。保留 14 天。
# 打包成功后、启动前，趁停着删一年前的定位和事件（traccar-prune.sh，删前的数据已在这次备份里）。
set -u
DEST=/root/traccar-backups
TS=$(date +%Y%m%d-%H%M)
# 停机标记：健康检查看到它（20 分钟内）才知道是备份在停，不去拉起；脚本怎么退出都删掉
LOCK=/run/traccar-backup.lock
touch "$LOCK"
trap 'rm -f "$LOCK"' EXIT
systemctl stop traccar
tar -czf "$DEST/traccar-$TS.tar.gz" -C /opt/traccar data conf
RC=$?
[ $RC -eq 0 ] && timeout 300 /usr/local/bin/traccar-prune.sh /opt/traccar/data 365  # 最多 5 分钟，卡住也照常启动
systemctl start traccar
[ $RC -eq 0 ] || { echo "$(date) backup failed rc=$RC" >> "$DEST/backup.log"; exit $RC; }
find "$DEST" -name "traccar-*.tar.gz" -mtime +14 -delete
echo "$(date) ok $(du -h "$DEST/traccar-$TS.tar.gz" | cut -f1)" >> "$DEST/backup.log"
