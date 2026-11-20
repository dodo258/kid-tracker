# 孩子在哪（家长极简页）

家长用手机打开 `https://gps.example.com/m/`，一屏看到孩子在哪、是多久前的位置、手机还有多少电。方案见 `docs/specs/2026-11-16-parent-page.md`，地址文字、常去地点、一键导航见 `docs/specs/2026-11-16-parent-page-places.md`，常去地点第二版（点圈管理、搜地址、图钉地址、我的位置）见 `docs/specs/2026-11-16-places-v2.md`，2026-11-17 重写（丝滑跟随、不动不跳、状态即时）见 `docs/specs/2026-11-17-parent-web-rewrite.md`。

- 底部状态卡两档（方案 `docs/specs/2026-11-18-parent-web-polish.md`，设计语言「地图上的一张便签」写在 `src/index.css` 开头）：**收起**（默认）三行——名字和电量（`ui/Battery.tsx`、`domain/battery.ts`：小电池按电量填充加百分比，平时灰；充电时闪电和电池安绿；不充电且 ≤20% 警橙加粗）；一句话状态（全页最大的字，`domain/status.ts`；前面的小圆点在路上实时安绿呼吸，刚停下、静止安绿不呼吸，失联警橙，手机时间不对、位置旧了灰）；在哪（地图实际采信的那个定位点的短地址，没有地址写定位方式和误差；最新一条只是心跳时写「上次位置 … · N 前」）+ 右边「刚刚 / 3 分钟前」（最后一次联系，心跳时写「刚刚在线」）。**展开**再加两个文字按钮「今天的轨迹」「设为常去地点」和四个入口：告警（右边「24 小时 7 条」，失联时写最近一次告警）、常去地点（N 个）、这次定位的详情、设置，点了整页滑入（`ui/route.ts`，地址带 `#/alerts/12` 这样的 hash，12 是孩子的设备号，Safari 返回手势、刷新、前进都认，恢复时换回那个孩子，标题带孩子名），左上角「返回」；推送不通或没查到时告警页顶上写「只更新到 几点」和重试。拖把手或卡片上下拖，松手按位置和速度吸附；点把手切换；收起时点卡片展开；电脑上滚轮一次挪一档、Esc 收起；碰地图时收起。记住上次的档（localStorage，读不到就收起）；地图镜头按露出的高度留边，圆点在露出那块的正中。
- 状态句（`domain/status.ts`）：「在家 · 静止 12 分钟」「在学校附近活动」「在路上 · 22 km/h 向东北」（实时，前缀圆点呼吸）「在路上 · 正在定位」「在路上 · 位置停在 6 分钟前」「刚停下 · 2 分钟」「已失联 18 分钟」（警色）「手机时间不对」「家长这边连不上服务器」（下一行「最后看到 16:40 · 点这里重连」）；刚回到前台、最新数据还没拉回来时写「正在更新…」（10 秒还没结果写「同步较慢 · 最后联系 几点」），「现在」先不往前走，不闪「已失联」；离线攒着的旧点刚补传上来写「刚收到补传，位置在 X 前」。**刚停下**是网页自己看的（`domain/stopped.ts`）：手机还报移动中，这一轮最新连着的卫星点测速都低于 1 米/秒、都在最新那个点 15 米内、跨了 30 秒以上，就写刚停下，不等手机改状态（旧上报端要 8–10 分钟）；只看这一轮在路上的点（中途报过静止就重算），停下后连着计步、又离开停车处就撤下。静止多久从这一轮第一条静止上报算，每台设备记住起点，30 分钟窗口滚动时不跳；打开页面时起点已经不在窗口里写「静止至少 X」（看到的不到 10 分钟只写「静止」）。旧版 App（没有 state）写「没在动」「15 分钟前的位置」「在路上 · 12 km/h」。
- 出错：服务器重启（5xx）、超时、断网都只说「暂时连不上服务器，30 秒后自动重试」，接口路径只打在浏览器控制台。网慢时首屏先显示「正在打开…」（`index.html` 里的静态内容，随系统深浅色）。
- 地址：卡片第三行写短地址（去掉省市区街道，服务器开了高德逆地理才有），全文在「这次定位的详情」页。告警列表里事件位置有地址的也带上。
- 常去地点：孩子在某个常去地点里时，名字后面直接写「在学校」，不再显示地址；地图上用淡色虚线圈加名字标出来。「设为常去地点」默认以孩子现在的位置为圆心；点「换个位置」进选点：屏幕中间固定一个绿色图钉，拖动地图把图钉对准地方（圈跟着走），点「用这里」回来。也可以直接在地图上长按（手机，按住约半秒不动）或右键（电脑）一处，打开时圆心就是那一点。名字点「家」「学校」或自己写，半径 100 / 200 / 500 / 1000 米（默认 200），选的时候地图上画绿色预览圈，并自动缩放到能看全整个圈。在高德、腾讯底图（GCJ-02）上选的点先转回 WGS-84 再存，OSM 底图上不转。
- 选位置时：顶部搜索框「搜地址、学校、小区」边输边出高德提示（限定孩子所在城市），点一条地图飞过去；图钉下面写「图钉位置：…」（地图停下 600 毫秒后查，查不到就显示操作提示）；直接选 100 / 200 / 500 / 1000 米，圈实时变、地图缩放到看全；已存的地点淡色虚线圈加名字照样画着，避免重叠；「我的位置」用家长手机的浏览器定位当图钉位置（拒绝或拿不到提示「拿不到你的位置，请拖地图选」）。
- 管理常去地点：点地图上的圈或圈上的名字，弹出地点卡片（名字、范围、地址），「改名」「改范围」（圆心不变，地图上实时预览）「删除」（先确认「删除后到达/离开不再提醒」；这个地点同时关联了账号下别的孩子时，确认框写「这个地点也用在 X 身上，删除后他们的到达/离开提醒也没了」，另给「只对 {当前孩子} 取消」，只去掉这一台的关联）。改名、改范围是 `PUT` 同一个地理围栏，id 不变，关联的设备和到达/离开提醒都保留。状态卡里「常去地点（N）」单独一块，默认折叠，每行点进同一张卡片。
- 卡片上只有「今天的轨迹」（选中那台今天 0 点以来）和「设为常去地点」。没有「定位到孩子」按钮：点圆点或切换孩子就是；地图被拖走、看轨迹以后右下角出「回到孩子」圆钮，点了回到正中。没有导航、刷新按钮（页面实时推送，断线时卡片自己说）。
- 圆点怎么动（方案「同类怎么做」，打车软件的做法）：在路上时比真实位置晚一个上报间隔，第 N 个点到了，圆点从第 N-1 个点沿直线匀速走过去，下一个点按时到时正好走到，看起来连续；下一个点迟到时按最后的速度和方向往前推，最多 10 秒、80 米；新点到了 1 秒内平滑校正，不瞬移。点来早了（几个一起到）重新分配时长，不越积越晚。离线补传的一批旧点按顺序快放（约 20 倍速，整批约 4 秒）。不在路上时新位置 0.8 秒滑过去，不外推、不画箭头。不管多远，圆点每秒最多走 400 米。方向箭头只在「实时」时画，往行进方向平滑转（走近的那边），地图转了按地图朝向换算。地图跟随孩子时镜头每帧追着圆点，圆点留在露出来那块的正中。系统开了「减少动态效果」时圆点直接跳、镜头直接对准、「实时」旁的圆点不呼吸。
- 跳点不拽偏（`motion/smooth.ts`）：在路上时用常速度卡尔曼滤波按上一段的速度和方向预测，新点按精度加权修正。隐含速度超过当前出行方式的上限（走路 4、骑车 12、坐车 45 米/秒）或偏离预测超过 max(1.5 倍精度, 30 米) 的点先不信：后面的点回到原方向就是毛刺，不画进轨迹线，圆点在这段沿原方向用预测位置补上；后面的点和它连得起来就是真拐弯、停下或加速，接受（最多晚 1 个点）；卫星点自带的测速方向和速度跟「上一个可信点到它」这一步对得上（方向差 30° 内、速度差 1.5 倍内）的，当场按真拐弯接受，不先当可疑点（跳点的测速方向还是原来的，对不上）。还没看到后面的点时圆点只沿原方向走、不往旁边拐。网络定位和精度 >50 米的点按 2 倍精度加权、不能单独定新方向；连着 4 个点都在别处又不回来才整体换过去。两个点隔 1 分钟以上不预测，跟着点走。原始数据不删。
- 不动时不跳：网络定位（App 标 `provider` 为 wifi、network，旧版 App 按精度 >50 米）是「大概位置」，落在圆点现在的精度圈里（取两个点精度里大的那个）就不挪圆点；明显更准（精度不到一半）的点照样挪。手机说人没在赶路（范围内活动、静止，或报移动中但运动摘要 `steps`、`bumps` 都是 0）时，网络点整体只说明人还在附近，一个都不挪（Wi-Fi 会一点点漂出几百米，只比相邻点挡不住），只有圈里、明显更准的能把圆点挪准一点。卫星点测速 <2 km/h、离圆点不超过两个点精度之和的是停着的抖动，也不挪。不挪的点换成圆点所在的点（标 `held`），播放器据此停住、不外推。选中那台画定位精度圈（半透明圆），网络定位时状态卡写「大概位置（Wi-Fi/基站定位）· 误差约 100 米」，精度按圆点停着的那个点算，和圈一致。
- 位置可能没跟上（`domain/lagging.ts`，方案 `docs/specs/2026-11-19-moving-wifi.md` 第 5 节）：手机把车载、随身 Wi-Fi 当成家里的，一直报「在附近活动」、不开卫星，圆点停在最后一个卫星点上。状态是在附近活动或静止（屋里定不到卫星时圆点停在早上的卫星点上）、最近 15 分钟内最新一条带网络估算（`netLat`、`netLon`、`netAcc`，上报端 v2 起；不是每条都带，最新一条没带就往前找）的估算离圆点超过 1 公里 + 估算误差时，第三行换成「位置可能没跟上，大概在 X 附近」（X 是估算位置的高德短地址，取到约 100 米查；还没查到或查不到写「大概在 3.2 公里外」，最多两行），地图画淡灰细实线估算圈（半径是估算误差，最小 100 米），第一次出现时把圆点和圈一起框进屏幕（之后不再挪地图，「回到孩子」照常）。圆点不按估算挪。失联、手机刚重启、正在更新、家长断网这些先说的情况不提示。假数据 `zonefar`、`stillfar`（最新一条没带估算、8 分钟前那条带了，都有 `-dark`）。
- 没有位置（`api/parse.ts` 标 `unlocated`）：手机从没定到过位置时只报时间，Traccar 把它存成纬度 0 经度 0、定位时间 1980-01-06（推送时标 `outdated`，但这个字段不进数据库，REST 拉回来只剩 0,0 和 1980），所以纬度经度都是 0 或定位时间早于 2000 年的点都算没有位置。这种点照样算联系时间、电量、状态和网络估算，但不画圆点、不进轨迹（最近 30 分钟和今天的）、不算距离和常去地点；整台设备都没有位置时卡片第三行写「还没有位置，手机还没定到」，有最近 15 分钟的网络估算就画淡色估算圈并把它框进屏幕；报着移动中写「在路上 · 正在定位」，不写「位置停在 X 年前」。假数据 `nofix`（有估算，有 `-dark`）、`nofixbare`（没估算）。
- 网络估算当位置（`domain/fixQuality.isNetEstimate`）：上报端 v2 屋里一直没卫星时，把确认过的网络估算放进主坐标（`provider` 为 network、精度是估算误差；v2 每条都带 `net`，旧版 App 不带，靠这个和旧版的网络点分开）。这种点不当 Wi-Fi 慢漂、也不当跳点：人静止也挪圆点（出了精度圈就挪），画误差圈（就是精度圈），卡片第三行写「文三路 152 号 · 网络估算 · 误差约 86 米」（最多两行，误差不被截掉），详情页定位方式写网络估算。假数据 `netmain`（有 `-dark`）。
- 移动中（含「位置停在 X 分钟前」）画最近 30 分钟的轨迹线，只画挪过圆点的点，画到圆点为止；看「今天的轨迹」时不画。打开页面、换孩子、刷新时补拉选中那台最近 30 分钟的点。
- 多台设备：地图上同时画出所有设备的点，每个点下面有名字小标签，点哪个点就选中哪台（状态卡跟着切），选中的点更大、名字加粗；顶部切换器（头像 + 名字）在 2 台及以上时一直显示，顺序是服务器返回的顺序，打开页面和换孩子时把选中的那个滚到中间；只有一台时顶部什么都不放。
- 设置页（展开后的「设置」，加载中、连不上服务器、账号下没有设备时卡片上也有「设置」）：地图底图（自动 / 高德 / 腾讯 / OSM）、深色 / 浅色（跟随系统 / 浅色 / 深色，存在本机，`html[data-theme]`）、完整后台（回 Traccar 原网页）、退出登录、版本号（打包日期 + git 提交号）。
- 登录一次记住：长期令牌存在这台手机的浏览器里，每天 04:30 Traccar 备份重启也不用重新登录。账号被停用、删除或令牌被吊销后，开着的页面最多 2 分钟回登录页，提示「登录已失效，请重新登录」。
- 深色模式默认跟随系统，设置里可以固定；可以「添加到主屏幕」，名字「孩子在哪」。地图最多缩到屏幕宽能看 60 个经度（约一个洲，`map/geometry.minZoomFor`：手机约 2.2 级、电脑约 3.9 级），不再能缩到整个地球。
- 只有常去地点会改服务器（新建、改名改范围、删除 Traccar 地理围栏，把新建的关联到当前设备，只对当前孩子取消关联），其余都只调查询接口。搜地址、查地址走服务器的 `/amap/` 转发（见下）。Traccar 里设成「只读」的账号不能添加或删除，页面会提示「这个账号只能看、不能改」。

## 技术

Vite + React + TypeScript + Tailwind，地图 MapLibre GL（显卡绘制，双指缩放、旋转顺手）+ 高德栅格瓦片。构建产物是纯静态文件，和 Traccar 同域名，接口不跨域。

| 目录 | 负责什么 |
|---|---|
| `src/api/` | 只收发和解析，不做判断：Traccar 和 `/amap/` 接口（`traccar.ts`）、返回数据校验（Traccar `parse.ts`、高德 `amap.ts`，含 `amapLocation`）、WebSocket 断线重连（`socketLoop.ts`）和定时查登录（`authWatch.ts`）、本机存的令牌、上次看的设备、状态卡档位、设置里选的底图和深浅色（`storage.ts`）、家长手机定位（`geolocate.ts`）、出错时给家长看的话（`errorText.ts`）；对外只有 `Api` 接口和数据类型（`types.ts`，含 `LatLon`） |
| `src/domain/` | 纯函数，全部单测：孩子现在怎么样 `presence.ts`（颜色档、实时/定位中/位置旧了、15 分钟失联、短地址）、卡片的状态句和在哪 `status.ts`、刚停下和静止多久 `stopped.ts`、详情页各行和没有地址时的定位方式 `details.ts`、一个点准不准要不要挪圆点 `fixQuality.ts`、最近 30 分钟的上报和定位点 `track.ts`（按定位时间插入、去重、挪过圆点的那串和轨迹线）、时间文字 `time.ts`、坐标转换 `coord.ts`（WGS-84 ⇄ GCJ-02）、选位置各来源的坐标 `pick.ts`、常去地点 `places.ts`、事件文案 `alarms.ts` |
| `src/motion/` | 圆点怎么动：跳点剔除和平滑 `smooth.ts`（常速度卡尔曼 `kalman.ts`）、播放器 `player.ts`（纯函数，时间由调用方传，单测用假时钟）、方向 `heading.ts`（方位角、转向走近的那边、地图旋转换算）；P20 两天真实上报的回放单测 `replay.test.ts` |
| `src/map/` | 只负责画：`MapView.tsx`（常去地点、选点预览和图钉、今天的轨迹、镜头）、底图和图层 `baseMap.ts`（高德 → 腾讯 → OSM，深色）、`geometry.ts`（坐标、最小缩放 `minZoomFor`）、圆点和精度圈的 DOM `kidMarker.ts`、逐帧循环 `useKids.ts`（每台一个播放器；圆点、箭头、精度圈、轨迹线、跟随镜头，停稳就停）、长按判定 `longPress.ts`、截图回放时记圆点位置的 `trace.ts`（正式包里没有） |
| `src/state/` | React 数据：`latest.ts` 设备、各设备最新一条上报和最近 30 分钟的点（纯 reducer），`useTracker.ts` 首次加载、WebSocket 实时更新、断线轮询、回前台刷新、重连补拉、补拉 30 分钟、告警和今天轨迹，`selected.ts` 选中设备的告警和今天轨迹（按设备号存、按 id 合并、按时间滚动，纯 reducer），`signOut.ts` 退出时服务器那两步，`usePlaces.ts` 常去地点的增删改，`useRegeo.ts` 高德查地址 |
| `src/ui/` | 界面：`App.tsx`（每台设备的 presence 和挪过圆点的点交给地图）、状态卡 `StatusSheet`（两档吸附 `sheetSnap.ts`）、二级页路由 `route.ts` 和壳 `SubPage`（告警 `AlertsPage`、常去地点 `PlacesPage` / `PlaceList`、详情 `DetailsPage`、设置 `SettingsPage`）、分段选择 `Segmented`、深浅色 `theme.ts`、设为常去地点 `PlaceSheet`、在地图上选位置 `PickSheet` 和顶部搜索框 `PlaceSearch`、地点卡片 `PlaceCard`、范围选择 `RadiusPicker`、登录页、切换器滚到中间 `scroll.ts` |
| `fixtures/` | 截图用假数据（按 Traccar 接口原样格式）和假后端 `mockApi.ts`；P20 真实上报 `p20.json` 和回放后端 `replay.ts`；只在 `--mode fixtures` 时加载，正式包里没有 |

依赖只往一个方向：`ui` → `map` → `motion` → `domain` → `api`（`api/types.ts` 的数据类型大家都用）；`state` 用 `api` 和 `domain`，给 `ui`。

### 用到的 Traccar 6.17 接口（按 v6.17.0 源码核实）

| 用途 | 接口 | 源码 |
|---|---|---|
| 登录 | `POST /api/session`（表单 `email`、`password`，开了两步验证再加 `code`；要验证码时回 401 + `WWW-Authenticate: TOTP`） | `SessionResource.add` |
| 生成令牌 | `POST /api/session/token`（表单 `expiration`，ISO 时间） | `SessionResource.requestToken` |
| 用令牌换会话 | `GET /api/session?token=` | `SessionResource.get` |
| 普通请求 | 请求头 `Authorization: Bearer <令牌>` | `SecurityRequestFilter` → `LoginService.login("bearer", …)` |
| 退出 | `DELETE /api/session`、`POST /api/session/token/revoke`（表单 `token`），分开做，401 算已经不算数 | `SessionResource` |
| 设备 / 最新位置 | `GET /api/devices`、`GET /api/positions` | `DeviceResource`、`PositionResource` |
| 事件当时的电量 | `GET /api/positions?id=…&id=…` | `PositionResource.getJson` |
| 今天轨迹 | `GET /api/reports/route?deviceId=&from=&to=`（`Accept: application/json`） | `ReportResource` |
| 最近告警、到达离开 | `GET /api/reports/events?deviceId=&from=&to=&type=alarm&type=deviceUnknown&type=geofenceEnter&type=geofenceExit` | `ReportResource` |
| 常去地点列表 | `GET /api/geofences?deviceId=`（同时关联到当前用户和这台设备的） | `ExtendedObjectResource.get` |
| 添加常去地点 | `POST /api/geofences`（JSON `{name, area}`，`area` 是 `CIRCLE (纬度 经度, 半径米)`，WGS-84）；创建者自动有权限，再 `POST /api/permissions` `{deviceId, geofenceId}` 关联到设备，关联失败就删掉刚建的 | `BaseObjectResource.add`、`PermissionsResource.add`、`GeofenceCircle` |
| 改名、改范围 | `GET /api/geofences/{id}` 取完整的一份，改 `name`、`area` 后整份 `PUT /api/geofences/{id}`（请求体带 `id`；Traccar 整行覆盖，不带的描述、日历、属性会被清空） | `BaseObjectResource.getSingle`、`update` |
| 删除常去地点 | `DELETE /api/geofences/{id}`；删之前对账号下别的设备各查一次 `GET /api/geofences?deviceId=`，看这个地点还用在谁身上 | `BaseObjectResource.remove` |
| 只对当前孩子取消 | `DELETE /api/permissions`（JSON `{deviceId, geofenceId}`，和关联时一样），地点和别的设备的关联不动（本机 6.17 实测 204） | `PermissionsResource.remove` |
| 定时查登录状态 | WebSocket 通着时每 2 分钟 `GET /api/devices`（带 Bearer 令牌，只看状态码），401 回登录页 | `SecurityRequestFilter` |
| 实时 | WebSocket `/api/socket`（靠会话 cookie），推 `{devices}`、`{positions}`、`{events}` | `AsyncSocketServlet`、`AsyncSocket` |

- 令牌有效期：不传 `expiration` 默认 7 天（`TokenManager.DEFAULT_EXPIRATION_DAYS`），传了没有上限；但用令牌登录的会话再申请令牌时，有效期不能晚于当前令牌（`requestToken` 里取较早的那个），所以令牌不能自己续期。本页登录时申请 **365 天**，到期回登录页提示「登录已失效」，再输一次密码。
- 停用、删除、吊销：Traccar 6.17 不断开已有的 WebSocket，WebSocket 通着时页面又不发普通请求，所以每 2 分钟带 Bearer 令牌请求一次 `GET /api/devices`。不用 `GET /api/session`：浏览器带着 WebSocket 用的会话 cookie，令牌吊销、账号停用后它照样回 200（`SessionResource.get` 没有令牌参数时只看会话；本机 6.17 实测：吊销、停用后 cookie 请求 `/api/session` 都是 200，Bearer 请求 `/api/devices` 吊销、停用、删除都是 401）。
- 重启：令牌是 ECDSA 签名，密钥存在数据库（`CryptoManager.loadOrGenerate` → `KeystoreModel`），Traccar 重启后旧令牌照样有效；内存里的会话会丢。所以普通请求每次都带 Bearer 令牌，不靠会话；WebSocket 断了就 30 秒后重新换会话再连，期间每 30 秒轮询。收到新连接的首帧才算连上（握手期间照常轮询）；断过以后重新连上，补拉选中设备最近 24 小时的告警、最近 30 分钟和今天的轨迹（WebSocket 只推之后的），和推来的按 id 合并。
- 推来的事件：Traccar 的 WebSocket 推这台设备的全部事件（上线、离线、移动…），页面只收和 REST 同样的四类（告警、失联、到达、离开，`LISTED_EVENTS`）；页面一直开着时告警按 24 小时滚出去，今天的轨迹过了午夜只留新的一天。告警和轨迹记着是哪台设备的，换孩子不串，点已选中的孩子不清空。
- 家长这边断网：WebSocket 断着、请求也失败，且上次同步（拉到数据或收到推送）超过 1 分钟时，第一行写黄色「家长这边连不上服务器，最后看到 16:40」，颜色档按最后同步那一刻判，不因为本地时钟往前走就显示孩子「已失联」。
- 手机时间快的点（定位时间比服务器收到时还晚 5 分钟以上）按服务器收到的时间排（`timelineTime`）：手机校时以后的正常点能成为最新、不被挤出 30 分钟窗口；失联先于「孩子手机时间不对」判。Traccar 自己只按定位时间换最新位置、推送，校时后的点不推，所以最新一条是这种点时每 30 秒补拉一次最近 30 分钟。
- 搜地址、查地址：同域名 `/amap/v3/assistant/inputtips`、`/amap/v3/place/text`、`/amap/v3/geocode/regeo`（Caddy 先拿请求带的令牌问 Traccar，没登录 404；服务器加 Key 转给 `restapi.amap.com`，网页里没有 Key，见 `docs/server-deploy.md`）。高德没有值的字段给 `[]`，`status` 是字符串 `"1"`；`location` 是 GCJ-02「经度,纬度」，传参最多 6 位小数。搜索的 `city` 用孩子当前位置 regeo 出来的 `addressComponent.city`（直辖市为空时用 `province`）。
- 坐标（选位置）：高德返回的一律 GCJ-02，存进 Traccar 前转 WGS-84（OSM 底图上也转）；拖图钉在高德、腾讯底图上本来就是 GCJ-02（查地址直接传、存之前转），OSM 底图上是 WGS-84（原样存、查地址前转）；浏览器定位是 WGS-84。`src/domain/pick.test.ts` 覆盖底图 × 来源全部组合。
- 速度：Traccar 存的是节，页面换成 km/h（×1.852），超过 1 km/h 算移动中。
- 「多久前」按位置的 `fixTime`（手机拿到这个点的时间）算，离线补传的旧点不会被当成新的。
- 坐标：Traccar 存 WGS-84；高德底图是 GCJ-02，画之前转换。高德瓦片连续加载失败会换 OSM 底图，这时不转换。
- 写权限：账号或服务器设成只读时，`POST /api/geofences` 回 400，正文 `Write access denied`；`POST /api/permissions` 回 400，正文 `Operation restricted`（`PermissionsService.checkEdit`、`checkRestriction`，`ResourceErrorHandler` 把异常都转成 400）。
- 「在学校」：位置的 `geofenceIds`（服务器算的）里有这个地点，或者位置落在圆里（本页按 Traccar `DistanceCalculator` 同一公式再算一遍，刚添加的地点不用等下一个点）。同时在几个圈里取最小的。
- 导航：`https://uri.amap.com/navigation?to=经度,纬度,名字&mode=car&callnative=1&src=parent-web`（[高德 URI 路径规划](https://lbs.amap.com/api/uri-api/guide/travel/route)）。这一页的文档没有 `coordinate` 参数（只有 marker 等页面有），所以坐标先在页面里转成 GCJ-02 再传。

## 本地开发与检查

```bash
cd parent-web
npm ci
npm run dev:fixtures      # 假数据，打开 http://127.0.0.1:5199/m/?state=normal
npm run typecheck && npm run lint && npm test && npm run build
npm run shots             # 截 8 种状态 + 两台同屏 + 常去地点 + 压测修复的几种状态 + 状态卡两档（sheet-*，查收起时卡片 ≤25%、圆点不被挡）+ 刚停下、二级页到 build/shots/（有 $AGENTQ_ARTIFACTS 就写那里；仓库的 .agentq/ui 末尾会调它）
npm run gestures          # 无头 Chrome 测地图手势：手机长按、电脑右键打开设为常去地点，点一下和拖动不打开，选点存进去的是 WGS-84；
                          # 点圈开地点卡片、改范围 PUT 的 area、删除先确认；选点调范围、搜地址、我的位置的坐标；
                          # 孩子在圈里时点他的圆点不弹地点卡片；共用地点删除先提醒、「只对小宝取消」只去掉关联；
                          # 实时移动时推一个新点：跟随时圆点一直在正中、不跳，拖过地图后圆点按上报间隔匀速走过去
                          # 状态卡两档：默认收起、点把手切、往上甩展开、刷新记住档位、往下拖收起、滚轮和 Esc；
                          # 点「告警」整页打开、返回和浏览器后退回地图；拖走地图出「回到孩子」；地图最多缩到看一个洲
npm run replay            # 无头 Chrome 里 30 倍速回放 P20 真实上报的 4 段（约 3 分钟）：逐帧查圆点不瞬移（相邻帧不超过每秒 400 米）、
                          # 屋里 Wi-Fi 漂移时圆点不动；截图、逐帧记录写到 build/replay（有 $AGENTQ_ARTIFACTS 就写那里）
```

重写新加的状态（各有 `-dark`）：`moving-trail`（实时，带箭头和最近 30 分钟轨迹线）、`stillwifi`（屋里网络定位，精度圈、「大概位置」，几个漂移的 Wi-Fi 点没挪圆点）、`stilllost-dark`、`backfill`（离线 21 分钟后推来一批补传的点，截快放到一半）。`?state=replay&from=…&to=…&speed=30` 回放 P20 真实上报（见 `fixtures/replay.ts`）。

打磨（2026-11-18）新加的（各有 `-dark`）：`stopped`（手机还报移动中、卫星点停了 2 分 20 秒，写「刚停下 · 2 分钟」）、`details`（这次定位的详情页）、`settings`（设置页）、`alerts-dark`；`sheet-{moving,still}-{peek,open}` 是状态卡两档。iPhone 模拟器 Safari 里看：`npm run dev:fixtures` 后 `xcrun simctl openurl <设备> 'http://127.0.0.1:5199/m/?state=stopped'`，深色用 `xcrun simctl ui <设备> appearance dark`，截图 `xcrun simctl io <设备> screenshot x.png`。

放后台再回来呼吸还对不对（iPhone 主屏幕网页曾变成一闪一闪，见 DECISIONS.md 2026-11-19）：`xcrun simctl io <设备> recordVideo x.mp4` 录着，`xcrun simctl launch <设备> com.apple.Preferences` 切走 30 秒再切回，`python3 scripts/breath-period.py x.mp4 <圆点中心 x> <y> <内半径> <外半径> <起始秒> <结束秒>` 量周期（iPhone 17 竖屏 Safari 里移动中的地图圆点约 `603 1082 80 150`、状态句小圆点 `72 2161 14 26`），切回后应仍是 2.40 秒。「添加到主屏幕」那种打开方式要点分享菜单，没有现成脚本，可以用 XCUITest 驱动 Safari 和桌面（`XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")`）。

假数据状态：`normal`（正常，两台设备）、`stale`（有点久）、`lost`（可能关机，带告警）、`lowbattery`（低电）、`track`（轨迹打开）、`alerts`（最近 24 小时页）、`login`（登录页）、`dark`（深色模式，截图时按深色渲染）、`multi`（两台设备同屏，打开时框住所有设备）；常去地点：`place`（小宝在学校，地图上有家和学校两个圈）、`places`（最近 24 小时页里看到达离开）、`addplace`（设为常去地点打开）、`pickplace`（在地图上选位置，有已存的地点和图钉地址）、`picksearch`（选点时搜「文三」出 3 条提示）、`placelist`（常去地点页）、`placecard`（点学校的圈打开的卡片）、`placeradius`（改范围选了 500 米的预览），各有 `-dark` 深色版；`addplace-wide` 是选了 1000 米、地图缩小到看全整个圈。压测修复：`longname`（四台设备、名字长，选中排在最后的「欧阳小宝贝的红米手机」，在学校；有深色版）、`future`（孩子手机时间往后错了 20 分钟）、`loading`（最近 24 小时和常去地点一直不回来）、`placeshared`（学校小宝、大宝共用，截图时点了「删除」）。移动中：`live`（小宝 12 km/h、位置 20 秒前，状态卡写「实时」；有深色版）、`movingold`（12 km/h、位置 6 分钟前，黄色「移动中，位置停在 6 分钟前」）；`normal`、`dark`、`track` 里小宝是静止的。三种状态（新版 App 的点带 `state`）：`moving`（实时 · 22 km/h · 向东北，圆点带方向箭头）、`zone`（在教工路 18 号附近活动）、`still`（静止 · 在线，最后确认 16:40 · 位置 15:20 起没变），各有深色版；`stilllost`（23 分钟没有任何上报，红色「已失联 23 分钟」）。电量和圆点颜色：`normal-dark`（深色的正常，`dark` 的数据本身在充电）、`charging`（小宝在充电，77%）、`lowbattery-dark`、`lost-dark`。审计修复：`parentoffline`（家长这边断网，有深色版）、`signoutfailed`（设置页里点退出，服务器没退成）、`nodevice`（账号下没有设备，也能退出）。`stale` 的位置故意没有地址，看地址行不出现的样子。`stale`、`lost`、`lowbattery` 的假账号只有一台设备，所以没有顶部切换器和「所有孩子」，用来看单设备的样子。时钟固定在 2026-11-16 16:42（北京时间）。

连一个**本机**测试用的 Traccar：`TRACCAR_URL=http://127.0.0.1:8082 npm run dev`（Vite 把 `/api` 和 WebSocket 转过去）。不要指向生产服务器。

推送延迟（服务器收到点到状态卡更新，目标 3 秒内）：`TRACCAR_URL=http://127.0.0.1:8082 OSMAND_URL=http://127.0.0.1:5055 TRACCAR_TOKEN=<本机令牌> DEVICE=<uniqueId> npm run latency`，灌 5 个点，分别打出「字出现」和「WebSocket 推送到达」的毫秒数。Traccar 6.17 默认 `server.buffering.threshold=3000`，推送本身就晚 3 秒；设成 0 后本机实测 5–11 毫秒（见 DECISIONS.md 2026-11-17）。

仓库根目录的 `.agentq/verify` 会跑 `npm ci`、类型检查、lint、单元测试和构建；地图手势测试 `npm run gestures` 要 Chrome，单独跑（没有 Chrome 时退出 125）。

### P20 真实上报（fixtures/p20.json）

2026-11-17 从生产服务器只读拷了一份 H2 数据库（`/opt/traccar/data/database.mv.db`，Traccar 照常运行，没改服务器），本机用服务器同版本的 h2 jar 导出 `tc_positions`，再用 `node scripts/p20-fixture.mjs positions.csv > fixtures/p20.json` 转换：坐标整体平移到杭州（第一个点放在假数据小宝的位置，点和点的距离、方向、漂移原样保留，不留孩子家的真实位置），attributes 只留 state、provider、batteryLevel、charge、alarm。导出命令写在脚本开头。

### 坐标转换和 sdk 对齐

`src/domain/coord.ts` 逐行移植自 `sdk/core/src/commonMain/kotlin/org/traccar/client/CoordinateTransform.kt`。`src/domain/coord.golden.json` 是 Kotlin 版 `wgs84ToGcj02` 对 15 个点（国内城市、边界、国外）跑出来的结果，`src/domain/coord.inverse.golden.json` 是 Kotlin 版 `gcj02ToWgs84` 对 19 个 GCJ-02 点（上面 15 个的结果加几个城市、边界、国外点）跑出来的结果，测试要求 TS 版误差小于 1e-12 度，另外在国内范围撒点要求 WGS→GCJ→WGS 往返误差小于 1 米。sdk 那边改了公式时重新生成：在 `sdk/core/src/commonTest/kotlin/org/traccar/client/` 临时放一个测试，对这些点调用 `wgs84ToGcj02`（或 `gcj02ToWgs84`）并 `println` 成 `[[lat, lon, gcjLat, gcjLon], …]`，跑 `cd sdk && ./gradlew :core:testAndroidHostTest --tests '<类名>'`，从 `sdk/core/build/test-results/testAndroidHostTest/TEST-<类名>.xml` 的 system-out 取出结果写回 JSON，再删掉临时测试。

## 部署

**一律用 `bash server/deploy-parent-web.sh`**：每次先 npm ci、类型检查、lint、单元测试、重新打包，再新目录 + 改名上线（旧版留 `/opt/parent-web.bak-<时间>`，只留最近 10 个），最后核对线上 JS 和本次打包一致。2026-11-16 曾用旧 dist 上线，漏掉了刚合并的功能。
（主会话或主人做；本任务没有登录服务器）

服务器情况见 `docs/server-deploy.md`：Caddy 配置 `/etc/caddy/Caddyfile`，静态文件放 `/opt/parent-web/`。**不要用 `rsync --delete` 之类的镜像删除同步**，下面用「新目录 + 改名」，旧版本留着可以秒回滚。

1. 本机构建：

   ```bash
   cd parent-web && npm ci && npm run build      # 产物在 parent-web/dist/
   ```

2. 上传到新目录（`/opt/parent-web.new` 不能已存在，否则 scp 会把 dist 放进它里面）：

   ```bash
   SSH="ssh -i ~/.ssh/tracker_ed25519 -o IdentitiesOnly=yes root@203.0.113.10"
   $SSH 'test ! -e /opt/parent-web.new' && \
   scp -i ~/.ssh/tracker_ed25519 -o IdentitiesOnly=yes -r dist root@203.0.113.10:/opt/parent-web.new
   ```

3. 换上新版本，旧版本改名留着：

   ```bash
   $SSH 'set -e
     chmod -R a+rX /opt/parent-web.new
     if [ -d /opt/parent-web ]; then mv /opt/parent-web /opt/parent-web.bak-$(date +%Y%m%d-%H%M%S); fi
     mv /opt/parent-web.new /opt/parent-web
     ls /opt/parent-web'
   ```

4. 第一次部署时改 Caddy（以后只换文件，不用动 Caddy）。先备份：`cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.bak-$(date +%Y%m%d-%H%M%S)`，在 `gps.example.com { … }` 里、转发到 8082 的那段**前面**加：

   ```caddyfile
   	redir /m /m/ 308
   	handle_path /m/* {
   		root * /opt/parent-web
   		header /assets/* Cache-Control "public, max-age=31536000, immutable"
   		header / Cache-Control "no-cache"
   		file_server
   	}
   ```

   然后 `caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile && systemctl reload caddy`。

   为什么是 `/m/*` 而不是方案里写的 `/m*`：`/m*` 会连 `/manifest.webmanifest` 这类以 m 开头的路径一起接走，Traccar 原网页根目录正好有这个文件（traccar-web v6.17.0 的 `index.html` 引用 `manifest.webmanifest`），完整后台的「添加到主屏幕」会坏。`/m` 不带斜杠时用 `redir` 补上。

5. 检查：

   ```bash
   curl -sI https://gps.example.com/m/ | head -1                      # 200
   curl -sI https://gps.example.com/m/manifest.webmanifest | head -1   # 200
   curl -s  https://gps.example.com/manifest.webmanifest | head -c 80  # 还是 Traccar 自己的
   ```

   手机打开 `https://gps.example.com/m/` 登录，看到地图和状态卡。

回滚：`mv /opt/parent-web /opt/parent-web.broken && mv /opt/parent-web.bak-<时间> /opt/parent-web`；Caddy 放回备份的 Caddyfile 再 `systemctl reload caddy`。旧的 `.bak-*` 目录确认不要了再手动删。

## 家长怎么用

- **iPhone 先添加到主屏幕再登录**：Safari 打开 `https://gps.example.com/m/` → 分享 → 添加到主屏幕，从桌面图标打开后登录。主屏幕上的网页 App 和 Safari 分开存数据：在 Safari 里登录的，到主屏幕图标里还要再登一次；而且 Safari 里网页存的数据 7 天没打开会被系统清掉，主屏幕上的不会（WebKit 的规则），所以主屏幕图标里登录才能真正「一次记住」。
- 令牌一年到期，到期会回登录页提示，再输一次密码。
- 退出登录：设置页里的「退出登录」（账号下没有设备、连不上服务器时卡片上有「设置」）。点了这台手机立刻看不到位置，再去服务器退出会话、吊销令牌，两步分开做；没退成（断网、服务器出错）显示「本机已退出，服务器上还没退出成功」，可以重试。
- 手机丢了：令牌只能用它本身来吊销。建议给每位家长的手机在完整后台单独建一个用户来登录本页，手机丢了在完整后台把那个用户停用或删掉即可：停用账号后，那台手机上开着的页面最多 2 分钟失效。这个用户要能添加常去地点就不能设成只读；设成只读的话，常去地点由管理员在本页或完整后台添加（关联到孩子的设备，并共享给这个用户）。
