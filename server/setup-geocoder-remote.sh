#!/bin/bash
# 由 setup-geocoder.sh 传到服务器执行；从 stdin 读高德 Web服务 Key。
# 先用 Key 试查一次地址，查得到才改 traccar.xml（先备份）并重启 Traccar。
set -euo pipefail
read -r KEY
CONF=/opt/traccar/conf/traccar.xml

# 杭州西湖（GCJ-02）试查，确认 Key 是 Web服务 类型且能用
RESP=$(curl -s -m 15 "https://restapi.amap.com/v3/geocode/regeo?output=json&location=120.1551,30.2741&key=$KEY")
case "$RESP" in
  *'"status":"1"'*) echo "Key 可用：$(echo "$RESP" | grep -o '"formatted_address":"[^"]*"')" ;;
  *) echo "Key 试查失败，没有改配置：$(echo "$RESP" | grep -oE '"info":"[^"]*"|"infocode":"[^"]*"' | tr '\n' ' ')"
     echo "（USERKEY_PLAT_NOMATCH = 建的不是「Web服务」类型；INVALID_USER_KEY = Key 复制错了）"; exit 1 ;;
esac

cp -a "$CONF" "$CONF.bak-$(date +%Y%m%d-%H%M%S)-before-geocoder"
python3 - "$CONF" "$KEY" <<'PY'
import re, sys
path, key = sys.argv[1:3]
s = open(path, encoding="utf-8").read()
s = re.sub(r'\s*<entry key="geocoder\.[^"]+">[^<]*</entry>', '', s)
s = re.sub(r'\s*<!-- 高德逆地理 -->', '', s)
# 关逆地理的旧设置（部署时为了不把坐标发第三方关掉的）也在上面一并去掉
block = (
    '\n    <!-- 高德逆地理 -->\n'
    '    <entry key="geocoder.enable">true</entry>\n'
    '    <entry key="geocoder.type">autonavi</entry>\n'
    f'    <entry key="geocoder.key">{key}</entry>\n'
    '    <entry key="geocoder.ignorePositions">false</entry>\n'
    '    <entry key="geocoder.reuseDistance">50</entry>\n'
    '    <entry key="geocoder.format">%f</entry>\n'
)
s = s.replace('</properties>', block + '</properties>')
open(path, 'w', encoding="utf-8").write(s)
PY
chmod 600 "$CONF"
systemctl restart traccar
sleep 15
systemctl is-active traccar
echo "已打开。孩子手机下一个点开始带地址（完整后台点设备能看到「地址」）。"
