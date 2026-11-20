#!/bin/sh
# 上报端 v2（app-v2/）加速压测，说明见 v2_stress.py 开头：
#   tools/stability/v2_stress.sh                          # 4 台模拟器 × 6.5 小时（约 7 小时，要 16 GB 空闲内存）
#   tools/stability/v2_stress.sh --lanes 1 --hours 0.5 --scale 0.3 --only reboot,storage_full   # 试跑脚本
#   tools/stability/v2_stress.sh --lanes 1 --hours 0.75 --kinds walk_home,car_wifi --only no_network   # 只试两个场景
#   tools/stability/v2_stress.sh --report-from build/stability-v2/<时间>                      # 重出报告
# 先打压测包（SKIP_BUILD=1 跳过）：app-v2 debug arm64，高德 Key 故意写错（练「高德报错切系统定位」），打完还原 local.properties
set -eu
cd "$(dirname "$0")/../.."
. .agentq/env.sh
APK=build/stability-v2/app-v2-stress.apk
case " $* " in *" --report-from "*) SKIP_BUILD=1 ;; esac
if [ "${SKIP_BUILD:-0}" != 1 ]; then
  props=app-v2/android/local.properties
  backup=$(mktemp)
  [ -f "$props" ] && cp "$props" "$backup"
  restore() { if [ -s "$backup" ]; then cp "$backup" "$props"; else rm -f "$props"; fi; rm -f "$backup"; }
  trap restore EXIT
  trap 'exit 130' INT TERM
  { [ -s "$backup" ] && grep -v '^AMAP_KEY=' "$backup"; echo; echo "AMAP_KEY=stability-test-invalid-key"; } > "$props.new" && mv "$props.new" "$props"
  (cd app-v2 && flutter build apk --debug --target-platform android-arm64)
  mkdir -p build/stability-v2
  cp app-v2/build/app/outputs/flutter-apk/app-debug.apk "$APK"
fi
# 不用 exec：上面的 trap 要在退出时把 local.properties 还原
python3 tools/stability/v2_stress.py --apk "$APK" "$@"
