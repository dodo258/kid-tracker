#!/bin/bash
# 给 Traccar 配 Telegram 通知：机器人 Token 只在这里输入，直接写到服务器，不经过别处。
# 用法：bash server/setup-telegram.sh
set -euo pipefail

HOST=root@203.0.113.10
SSH=(ssh -i "$HOME/.ssh/tracker_ed25519" -o IdentitiesOnly=yes -o BatchMode=yes "$HOST")
PREFIX_FILE="$HOME/.config/gps-tracker/tg-relay-prefix"
[ -s "$PREFIX_FILE" ] || { echo "缺少 $PREFIX_FILE（中转密钥）"; exit 1; }
BASE_URL="https://tgrelay.example.com/$(cat "$PREFIX_FILE")/bot"

read -rsp "粘贴机器人 Token（输入时不显示）：" TOKEN; echo
[[ "$TOKEN" =~ ^[0-9]+:[A-Za-z0-9_-]+$ ]] || { echo "Token 格式不对"; exit 1; }
read -rp "你的 Telegram 数字 ID（@userinfobot 里看到的 Id）：" CHAT_ID
[[ "$CHAT_ID" =~ ^-?[0-9]+$ ]] || { echo "ID 应该是纯数字"; exit 1; }

# 远端脚本先传上去（不含任何密钥），Token 经 stdin 传，不出现在命令行参数里
scp -q -i "$HOME/.ssh/tracker_ed25519" -o IdentitiesOnly=yes "$(dirname "$0")/setup-telegram-remote.sh" "$HOST:/root/setup-telegram-remote.sh"
printf '%s\n%s\n%s\n' "$TOKEN" "$CHAT_ID" "$BASE_URL" | "${SSH[@]}" bash /root/setup-telegram-remote.sh
