#!/bin/sh
# 一条命令跑全部稳定性场景（无 GMS 模拟器 gps-nogms，测试数据只发本机记录服务）：
#   tools/stability/run.sh                 # 全部场景，一个接一个（约 6 小时）
#   tools/stability/run.sh --jobs 4        # 4 台模拟器同时跑（约 1.5 小时，要 16 GB 空闲内存）
#   tools/stability/run.sh 4 6             # 只跑场景 4 和 6
# 结果在 build/stability/<时间>/report.md；全部通过退出码 0。
set -eu
cd "$(dirname "$0")/../.."
. .agentq/env.sh
exec python3 tools/stability/stability.py "$@"
