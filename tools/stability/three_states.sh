#!/bin/sh
# 三种状态模拟器场景（约 75 分钟）：tools/stability/three_states.sh [--apk <debug APK>]
set -eu
cd "$(dirname "$0")/../.."
. .agentq/env.sh
exec python3 tools/stability/three_states.py "$@"
