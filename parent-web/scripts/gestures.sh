#!/bin/sh
# 无头 Chrome 测地图手势：手机长按、电脑右键都能打开「设为常去地点」，点一下、拖动不会；选点「用这里」存进去的是 WGS-84；
# 点圈打开地点卡片、改范围 PUT 的 area、删除先确认；选点时调范围、搜地址、我的位置的坐标。
# 用 fixtures/ 假数据，不碰 Traccar 服务器。退出码 125 = 这台机器跑不了（没有 Chrome）。
set -eu
cd "$(dirname "$0")/.."
. scripts/serve-fixtures.sh

node scripts/gestures.mjs "$URL"
