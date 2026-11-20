# 模拟上报端

按场景造数据，测服务器和家长页；以后重写上报端时也拿它当依据。方案：`docs/specs/2026-11-18-simulator.md`。
只用 Python 3 标准库；回放检查另外要 Node（`parent-web` 的依赖）和 Google Chrome。

每个场景算出四样东西：

| 产物 | 文件 | 说明 |
|---|---|---|
| 真实轨迹 | `truth.json` | 每秒一个真实位置，`[秒, 纬度, 经度, 段类型, 在不在路上]` |
| 手机会报的点 | `reports.json` | 带 `state`、`motion`、`provider`、精度、`steps` / `bumps` / `wifiType` 运动摘要、`net` 联网方式，`arrive` 是服务器收到的时刻，`tags` 标出干扰（jump、drift、backfill、resend…） |
| 服务器存下的点 | `positions.json` | 按生产 Traccar 6.17 的处理和过滤配置算（`traccar.py`：filter.future=86400，filter.duplicate 11-18 12:28 起关掉；生产改了要同步），就是家长页 fixtures 的格式；被丢掉的在 `dropped.json`（原因 future / no-last）。每个点另算推不推给网页（`pushed`：定位时间早于当时最新位置的晚到旧点只入库、不推，和 Traccar 的 PostProcessHandler 一样；回放的假服务器 `parent-web/fixtures/traccarPush.ts` 同一条规则） |
| 传感器时间线 | `sensors.csv` | 和 P20 上「传感器录制（测试用）」同一个格式（2 秒一行，表头和上报端 v2 的 SDK `app-v2/sdk` 的 `SnapshotCsv.COLUMNS` 一致，测试里核对；走路时 `acc_peaks` 一步一个）；手机就是按它判断在不在动 |

## 用法

```sh
cd tools/simulator
python3 sim.py list                         # 有哪些场景、多长
python3 sim.py generate [场景…] [--out 目录]   # 只生成上面四样（默认写 build/simulator/data-<时间>/）
python3 sim.py replay [场景…]                # 回放检查当前仓库的 parent-web，全部场景约 20 分钟
python3 sim.py replay --web <另一个 parent-web 目录>   # 检查别的分支的家长页（要先在那边 npm ci）
python3 sim.py judge <replay 的输出目录>       # 改了检查规则后不重跑浏览器，按记下的帧重新判
python3 sim.py export [场景…]                # 把服务器存下的点导成 parent-web/fixtures/sim/<场景>.json
python3 sim.py golden                       # 重新生成和上报端 v2 判断层对齐的金样本（改了 reporter.py、phone.py、sensors.py 后跑）
python3 -m unittest discover -s .           # 单元测试，几秒（已在 .agentq/verify 里）
```

### 回放检查（全自动，不碰服务器）

`replay` 起家长页的 fixtures 模式（`vite --mode fixtures`），在无头 Chrome（390×844）里打开回放模式 `?state=replay`，
用 DevTools 拦截把回放读的 `fixtures/p20.json` 换成场景的点（家长页代码不用改），按场景里的倍速播放，
逐帧记下圆点（页面 fixtures 模式写的 `window.__dotTrace`）和状态卡第一行的每次变化，再由 `checks.py` 逐条判（页面实时收到的只算推送的点）：

- 圆点离真实轨迹不超过 max(20 米, 2 倍当时精度)。圆点本来就晚一个上报间隔、隧道里会停在入口，所以量的是离「过去 10 分钟走过的路」多远，落后多少秒另列；
- 圆点不落后：页面 20 秒（2 个上报间隔）以前就收到的卫星点（跳点除外），圆点沿真实轨迹已经走到（按路程量，往回不超过 max(20 米, 5 秒的路)；
  按秒量不行，原地等车时真实轨迹几分钟都在同一点）。
  隧道、断网时页面没新点自然不要求，恢复联网后 3 分钟的补传快放归下面那条管。只有偏离那条时，圆点全程停在起点也能通过；
- 真实在原地（stay 段，开头留 30 秒）时圆点一共挪动不超过 5 米；
- 侧跳点到达后 40 秒内圆点离轨迹不超过 10 米 + 附近正常卫星点本身的误差；
- 拐弯后第 2 个点到达、再过一个上报间隔加 3 秒，圆点已经在拐弯后的路上；
- 恢复联网后 3 分钟内圆点沿轨迹只往前走（往回不超过 5 秒的路），并追到断网结束的位置；
- 状态变了的点到达后 3 秒，第一行已经是新状态（移动中、在附近活动、静止，手机时间快 5 分钟以上时是「孩子手机时间不对」）；
- 最后一次上报不到 15 分钟不出现「已失联」，超过 15 分钟要出现（只查带 state 的新版点）；
- 服务器一个点都没存、浏览器没采到圆点帧：这个场景报「没有数据」不通过，其他场景照常判；
- 截图里地图画完：地图区（图上半部分）没有露出容器底色 #ece9e1 的整块（`shots.py`，48 像素一格，98% 是底色算灰块）。
  截图前先等底图瓦片请求落定（最多 4 秒）：加速回放时跟随镜头走得快、补传快放一下跳很远，行进方向前沿的瓦片来不及下载，
  不等就会截出灰块；等了 4 秒还在下载的，报告里注明。

加速回放带来的限制（判的时候已经考虑）：页面打开要几秒真实时间，那之前看不到页面，不判；页面收到点到第一行变要几十毫秒真实时间，
倍速高时「3 秒」按 0.15 秒真实时间换算放宽，下一个点紧跟着到的看不出来、跳过并计数；页面 30 秒（真实时间）才刷新一次「现在」，
「该出现失联」按倍速放宽（误报失联照严格查）。要严查这几条就把倍速调低（`--speed`）。

结果写到 `$AGENTQ_ARTIFACTS/simulator-replay/`（没有就是 `build/simulator/replay-<时间>/`）：`report.md`（每个场景的偏离统计、
每条检查、截图文件名）、`result.json`，每个场景一个目录放截图和 `trace.json`。有不通过的退出码 1，没有 Chrome 退出码 125。
不在 `.agentq/verify` 里，单独跑。

### 实时上报（端到端）

```sh
python3 sim.py live <场景> --device <设备号> [--url http://127.0.0.1:5055]   # 默认报到本机 Traccar
python3 sim.py live offline-backfill --device 90000001 --prod                 # 报到生产的测试设备
python3 sim.py verify <live 的记录目录>                                       # 重新核对一次
```

按真实时间（场景从现在开始）把每个点用 OsmAnd 协议 POST 出去，表单参数和 App 的 `HttpUploader` 一样（速度按节）；
断网补传的点按补传时刻一次发出（时间戳还是当时的）。每条的发送时刻和响应记在 `sent.jsonl`。

`--prod` 跑完会自动核对：SSH 到服务器（`~/.ssh/tracker_ed25519`），把 H2 数据库拷到 `/tmp` 只读打开（查完删掉，不碰正在用的库），
取回测试设备这次发送时间窗里存下的点、事件、提醒关联、服务器日志（处理过的点、被过滤的点），按「实际发成功的上报 + 服务器模型」
算出应该存哪些，逐条对照：有没有丢、有没有多存、顺序、呼吸点有没有抄上一个点的定位时间、每个点都带 state，以及测试设备没有关联任何
Telegram 提醒。运行中的 H2 文件直接拷（H2 官方不支持这样备份），副本可能缺最后写的几行：副本里没有、服务器日志里有一行对得上的
（一行日志只顶一个点），记成「待确认」，结果写「待确认」不写「一致」，过几分钟用 `sim.py verify <目录>` 再核对。
结果写 `verify.md`：一致退出码 0，不一致或待确认退出码 1。

**生产保护**：不加 `--prod` 只能报到本机地址（127.0.0.1 / localhost）；加了 `--prod` 设备号只能是测试设备 `90000001`、
地址只能是 `https://gps.example.com/osmand`，而且只能按真实时间报，别的组合直接报错退出（退出码 2）。
测试设备「模拟测试」只关联主人账号，5 条 Telegram 提醒只关联 P20，测试设备的点不会推提醒（`verify` 每次都查）。

## 场景格式

`scenarios/<名字>.json`，时间都是「离场景开始多少秒」，坐标 WGS-84，起点默认是 fixtures 里小宝的虚构位置（杭州），不用孩子真实的家和学校：

```json
{
  "name": "walk-school-jumps",
  "title": "一句话说明",
  "start": "2026-11-20T07:20:00+08:00",
  "seed": 11,
  "speed": 20,
  "segments": [
    {"kind": "stay", "seconds": 150},
    {"kind": "walk", "legs": [[90, 320], [0, 260]]},
    {"kind": "bus", "legs": [[90, 1500]], "stops": [[700, 90]]},
    {"kind": "indoor", "seconds": 600}
  ],
  "events": [
    {"type": "side_jump", "at": 330, "points": 1, "meters": 80},
    {"type": "no_fix", "from": 820, "to": 910}
  ]
}
```

- `seed` 固定随机数（定位误差、精度），同一个场景每次生成一样；`speed` 是回放倍速，`tail` 是最后再放多少秒（默认 30）。
  `fresh: true` 是全新装：手机手里没有上一个卫星点（服务器上也没有这台设备的位置），不写就是手机早就在跑。
  `restored: "still"` 是场景开始时 App 刚重启（装更新、进程被杀）、接着重启前的静止（和 App 一样只接静止），第一次就呼吸。
- 段 `kind`：`stay` 原地不动、`indoor` 屋里来回走（半径 8 米）、`walk` 1.4 米/秒、`bike` 4.5 米/秒、`bus` 9 米/秒。
  走的段写 `legs`（`[方位角, 米]`，正北 0 顺时针），`stops` 是公交等红灯（`[走过多少米, 停几秒]`），`mps` 改速度；
  不动的段写 `seconds`。可选 `fix`（这段手机拿到的定位：`gps` / `wifi` / `cell` / `none`，默认走的段 gps、不动的段 wifi）、
  `acc`（精度范围 `[最小, 最大]` 米）、`wifi`（连的 Wi-Fi：`fixed` / `hotspot` / `car` / `none`；`car` 是车机自带的 Wi-Fi，
  整个场景同一个 BSSID、不计费、名字不像热点）、`charging`。
- 干扰 `events`：

| type | 参数 | 手机这时会怎样 |
|---|---|---|
| `side_jump` | `at`, `points`, `meters` | at 之后报出去的 points 个卫星点往右侧偏 meters 米（精度照常，最难分辨） |
| `drift` | `from`, `to`, `meters`, `bearing` | 网络定位误差一路漂到 meters 米外 |
| `no_fix` | `from`, `to` | 隧道 / 地铁：没有定位，在动时每 60 秒报一个只带时间的点 |
| `cold_start` | `from`, `to` | 没有卫星点，只有基站定位（15 秒一个） |
| `offline` | `from`, `to`, `resend_first` | 断网：上报攒着，to 时一次补传；resend_first 时断网前最后一条再发一次 |
| `breath_delay` | `at`, `seconds` | 华为推迟呼吸：at 之后那次呼吸晚 seconds 秒 |
| `killed` | `from`, `to` | 进程被杀：不定位、不上报、不录传感器，to 时重启马上报一次 |
| `airplane` | `from`, `to` | 飞行模式：全停，to 时恢复 |
| `clock` | `from`, `to`, `seconds` | 手机时间快 seconds 秒（负数是慢） |
| `wifi_wrong` | `from`, `to`, `east`, `north` | Wi-Fi 位置库记错：网络定位都落在 from 时位置偏 (east, north) 米的固定点 |
| `misjudge_moving` | `from`, `to`, `interval` | 旧版上报端把屋里误判成移动中，每 interval 秒报一个网络定位点 |

- 真实回放场景（`p20-real.json`）不写段，写 `"real": "<parent-web/fixtures 下的文件>"`、`from`、`to`：用服务器真实存下的点，
  没有真实轨迹，只查状态行、失联、「报静止时圆点不挪」和「报的测速连续接近 0 时圆点不挪」（连着两个以上卫星点测速 <3.6 km/h，如 `p20-ride-1118` 等红灯）。

**加新场景**：在 `scenarios/` 放一个 JSON，`python3 sim.py generate <名字>` 看 `reports.json` 是不是你想要的样子，
再 `python3 sim.py replay <名字>`。要导成家长页 fixtures 就 `python3 sim.py export <名字>`。
新的干扰类型在 `scenario.py` 的 `EVENT_TYPES` 登记，在 `phone.py`（手机怎么反应）实现，`test_simulator.py` 加一条测试。

## 上报端行为（`phone.py` + `reporter.py`）

手机就是上报端 v2 的判断层（方案 `docs/specs/2026-11-18-reporter-sensor-first.md` 第 3、3a、4 节）：`reporter.py` 是
`app-v2/sdk` 里 `motion/MotionClassifier`、`policy/ReportPolicy`、`policy/Judge` 的 Python 版。`phone.py` 每 2 秒把传感器时间线
（`sensors.py`）的一行喂给它，它说开卫星就每秒把这一秒拿到的点（场景段的 `fix`）给它，说取网络定位就给一个网络定位点（室外按 Wi-Fi 定位算），
它说报什么就报什么。所以现在的节奏是：
- 判断：口袋里走路几秒判出（大幅摆动或走路节奏），有动静、没走路节奏 1 分钟算坐车（或卫星速度 > 3 m/s），完全不动 2 分钟静止，
  其余是屋里走动；飞行模式全停。还连着上次卫星定位时的 Wi-Fi（锚，记着那个卫星点的位置）时走动、坐车也算屋里，但最近 3 分钟里
  累计走路坐车满 60 秒（跟着人走过的 Wi-Fi 20 秒）就开卫星量一次离锚点多远（最多 90 秒，量的点不报）：超出 200 米（加两边精度）
  就是 Wi-Fi 跟着人走（车载 Wi-Fi、随身 Wi-Fi），锚清掉、马上转在路上报这个点；明显就在锚这里（100 米内）冷却 10 → 20 → 30 分钟；
  没定到或说不清 5 分钟后再量。屋里取的网络估算离锚点超过 1 公里加估算精度也量一次（方案 `docs/specs/2026-11-19-moving-wifi.md`）。
- 主坐标（lat/lon）只放卫星点；网络定位只报时间，估算放 `netLat`、`netLon`、`netAcc`、`netSource`（方案 3a 定稿）。
  例外：10 分钟没报过卫星点（或从没有过主坐标），可信的网络估算（误差 ≤200 米、不是热点和跟着人走过的 Wi-Fi 下的）隔 ≥60 秒两次一致、
  离上一个主坐标超过 300 米加误差，就当主坐标报一次（`provider` 写 `network`，`tags` 带 `estimate`）；连着有卫星锚点的 Wi-Fi 时不顶。
  等确认时屋里、静止马上再取一次网络定位（不等 5 分钟），这个 Wi-Fi 还没有锚就拿估算当临时锚点。每条都带最近 10 分钟内的可信估算。
- 走路 / 坐车：马上开卫星、报状态，之后每 10 秒报最新的卫星点；刚开卫星 30 秒没定到、或定到过又丢了 10 秒，先报网络点；
  什么点都没有每 60 秒报一个只带时间的点；卫星 3 分钟搜不到停 2 分钟再试，停着时每分钟取一次网络定位。
- 屋里走动：不开卫星，每 5 分钟取一次网络定位报上去；静止：之后 5 分钟呼吸一次（只带时间）。从路上停下转进来时马上取一次，
  屋里和静止互转不取（人没换地方）。静止时主坐标过时（10 分钟没报过卫星点或从没有过），估算还没定下来或定下来过了 15 分钟，
  呼吸那一刻顺带取一次网络定位，这次呼吸带着它报；静止时取网络定位每小时最多 4 次（场景 `still-after-update`）。
- 连着热点（场景 `wifi: hotspot`，名字 AndroidAP、计费）时不用高德的 Wi-Fi 定位；固定 Wi-Fi 一个地方一个 BSSID（中间在路上过就换）；
  模拟的手机没卡，读不到基站（和 P20 一样，「锚这里没见过的基站」那条只有 SDK 单测）；
  场景开始时手机早就在跑，手里有刚才最后一次卫星定位的点（服务器上有上一个卫星位置）；`fresh` 场景没有。
- 离线队列先进先出：断网恢复后，积压的补传完才发新点。
- `misjudge_moving`（旧版上报端误判）、`breath_delay`（华为推迟闹钟）是注入的干扰，判断层照跑，只是报出去的按干扰来。

和 Kotlin 那份是不是一样由金样本保证：`python3 sim.py golden` 把 `build.GOLDEN_SCENARIOS` 这几个场景喂给判断层的输入和它的判断写进
`app-v2/sdk/core/src/commonTest/resources/simulator/`，SDK 的 `SimulatorGoldenTest` 用 Kotlin 判断层重放、逐行比对；`test_simulator.py`
核对金样本是现在的代码算出来的。改判断规则时两边一起改，再重新生成金样本。

运动摘要用 `steps`（这次和上次上报之间的步数）、`bumps`（这之间晃动的秒数）、`wifiType`；`motion` 是判断（walk / vehicle / fidget / still）。
**不能叫 `wifi` 或 `cell`**：那是 OsmAnd 协议的保留参数（`wifi=mac,rssi`），Traccar 6.17 解析 `wifi=none` 会数组越界、整条丢掉（2026-11-18 实测 502）。
联网方式用 `net`：上报那一刻连的是 `wifi`（Wi-Fi）、`hotspot`（手机热点、随身 Wi-Fi）、`cellular`（移动网络）还是 `none`（断网、飞行模式），按传感器时间线的联网列和 Wi-Fi 种类算；家长页「详细信息」的网络一行读它，`wifiType` 先留着兼容（方案 `docs/specs/2026-11-18-parent-web-info-sheet.md` 第 3 节）。

## 文件

- `sim.py` 命令行；`scenario.py` 场景和真实轨迹；`phone.py` 手机（定位误差、干扰，按判断层上报）；`reporter.py` 判断层（上报端 v2 的 Python 版）；`traccar.py` 服务器模型；
  `sensors.py` 传感器时间线；`build.py` 生成和写盘；`checks.py` 回放判断；`replay.mjs` 浏览器那一半（复用 `parent-web/scripts/cdp.mjs`）；
  `live.py` 实时上报、生产保护和核对；`test_simulator.py` 单元测试。
