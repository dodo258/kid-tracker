#!/bin/bash
# 给 Traccar 打开高德逆地理（每个位置带中文地址）：高德「Web服务」Key 只在这里输入，直接写到服务器。
# Key 在 高德开放平台 → 控制台 → 应用管理 → 我的应用 → 添加 Key → 服务平台选「Web服务」 里建。
# 用法：bash server/setup-geocoder.sh
set -euo pipefail

HOST=root@203.0.113.10
KEYS=(-i "$HOME/.ssh/tracker_ed25519" -o IdentitiesOnly=yes -o BatchMode=yes)

read -rsp "粘贴高德 Web服务 Key（输入时不显示）：" KEY; echo
[[ "$KEY" =~ ^[0-9a-f]{32}$ ]] || { echo "Key 应该是 32 位小写字母和数字"; exit 1; }

scp -q "${KEYS[@]}" "$(dirname "$0")/setup-geocoder-remote.sh" "$HOST:/root/setup-geocoder-remote.sh"
printf '%s\n' "$KEY" | ssh "${KEYS[@]}" "$HOST" bash /root/setup-geocoder-remote.sh
