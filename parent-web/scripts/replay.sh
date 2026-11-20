#!/bin/sh
# 用 fixtures/ 起 dev server，由 scripts/replay.mjs 在无头 Chrome 里 30 倍速回放 P20 真实上报，逐帧查圆点不瞬移、不动时不跳，存截图。
# 输出到 $AGENTQ_ARTIFACTS（没有就是 parent-web/build/replay），约 3 分钟。退出码 125 = 这台机器跑不了（没有 Chrome）。
set -eu
OUT="${AGENTQ_ARTIFACTS:-}"
case "$OUT" in "" | /*) ;; *) OUT="$PWD/$OUT" ;; esac
cd "$(dirname "$0")/.."
OUT="${OUT:-build/replay}"
mkdir -p "$OUT"
. scripts/serve-fixtures.sh

node scripts/replay.mjs "$URL" "$OUT"
