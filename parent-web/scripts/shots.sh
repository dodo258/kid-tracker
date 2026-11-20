#!/bin/sh
# 用 fixtures/ 假数据起 dev server，再由 scripts/shots.mjs 让无头 Chrome 按 390×844（iPhone 14/15 宽度，2 倍屏）
# 截方案第 5 节的 8 种状态、两台设备同屏（multi）和常去地点的几种状态（浅色、深色）。
# 输出到 $AGENTQ_ARTIFACTS（没有就是 parent-web/build/shots），文件名 parent-<状态>.png。
# 地图瓦片要从高德实时加载（截图机要能上网）；不碰 Traccar 服务器。退出码 125 = 这台机器跑不了（没有 Chrome）。
set -eu
# $AGENTQ_ARTIFACTS 是相对路径时按调用时的目录算
OUT="${AGENTQ_ARTIFACTS:-}"
case "$OUT" in "" | /*) ;; *) OUT="$PWD/$OUT" ;; esac
cd "$(dirname "$0")/.."
OUT="${OUT:-build/shots}"
mkdir -p "$OUT"
. scripts/serve-fixtures.sh

node scripts/shots.mjs "$URL" "$OUT"
