# 上报端 v2（app-v2/）

主人 2026-11-18 定：上报端以**原版**为底重建，不在根目录那个改了 1100 多次的 App 上接着改。方案见 `../docs/specs/2026-11-18-reporter-rebuild-from-upstream.md`，已做第一步 R1「基础」（只搭底座）、第二步 R2「定位源」（高德为主、系统定位兜底）、第三步 R3「判断层」（传感器优先，方案 `../docs/specs/2026-11-18-reporter-sensor-first.md` 第 3、3a、3b、4 节）、第四步 R4「保活」（呼吸闹钟、开机和更新后自启、看门狗）、第五步 R5「告警」（求救、关追踪、低电、关机、关定位，先存再发）和第六步 R6「界面和防护」（设置、App 密码、防卸载、自检清单自动判定、检查更新、日志、测试用录制），之后加了「没解锁也恢复上报」（Direct Boot），第七步做了可靠性自查、故障注入和加速压测。

## 里面是什么

| 目录 | 来源 | 改了什么 |
|---|---|---|
| `lib/`、`android/`、`ios/`、`pubspec.yaml` 等 | 原版 App github.com/traccar/traccar-client，提交 `94dd73f`（我们的分叉点） | 见下面「和原版的差别」 |
| `sdk/` | 原版 SDK github.com/traccar/traccar-client-sdk **v1.1.4**（本仓库 `6389aa5` 以 subtree 引入时的 `sdk/`） | R1 一行没改；R2 换了定位源，R3 加了判断层、换了引擎，见下面两节 |

App 用本地路径依赖 SDK：`pubspec.yaml` 的 `traccar_client_sdk: path: sdk/flutter`；`android/settings.gradle.kts` 用 `includeBuild("../sdk")` 把插件依赖的 `org.traccar:traccar-client-sdk` 换成 `sdk/core` 源码。

### 和原版的差别（R1）
- 包名 `com.dodo258.tracker`、应用名「定位服务」、深链 `com.dodo258.tracker://`：和旧 App 一样，才能覆盖安装。
- 只留中文、英文两份 ARB（`lib/l10n/`），系统是别的语言时用中文；标题和前台通知文字（「服务中」）走 ARB。
- 去掉 Firebase（推送、统计、崩溃上报）和评分弹窗；扫码从 mobile_scanner（只有 ML Kit 实现，带 Firebase 类）换成 flutter_zxing。打出的包里没有 `com.google.firebase` 类。
- 新装默认服务器 `https://gps.example.com/osmand`；其余默认值（精度、间隔、距离）仍是原版的。
- 只打 arm64：`android/app/build.gradle.kts` 打包时去掉其他 ABI。
- 正式签名读仓库外的 `~/.config/gps-tracker/key.properties`（和旧 App 同一把，证书 SHA-1 `6f78a5dcd77d0f64c03040b1f48783a209d74e2c`）；没有这个文件时 release 退回 debug 签名并打印提示。
- 版本 `11.0.0+300`：比旧 App（10.2.x，versionCode 187）大，中间留出旧 App 修严重问题的号。
- 关掉系统备份（`android:allowBackup="false"`）：设置里有设备号和密码，换机恢复会让两部手机报同一个设备号。
- 覆盖安装旧 App 后的数据接着用，见下一节。
- 构建工具：AGP 9.4.1、Kotlin 2.4.20、Gradle 9.8.0，和原版 SDK 的 `sdk/gradle/libs.versions.toml` 对齐（composite build 里只能有一个 AGP 版本）；旧 App 是把 SDK 降到 9.1.1，这里反过来升 App，SDK 保持原样。

## 第二步：定位源（R2）
高德为主、系统定位兜底，放在 `sdk/core`。每一样「原版为什么没有、不加会怎样」：

| 加 / 删 | 原版为什么没有 | 不加（不删）会怎样 |
|---|---|---|
| **高德定位** `AMapLocationSource`：GCJ-02 转 WGS-84（`CoordinateTransform`，从根目录 SDK 原样搬来，测试一起搬） | 原版面向有 Google 服务的手机，用 Fused | 国内手机只剩系统定位：没有网络定位的手机室内基本没点；高德坐标不转会偏几百米 |
| **高德报错切系统定位** `FallbackLocationSource` + `ProviderFallbackPolicy`（从根目录搬，判定规则和 P20 实测过的一样：Key 错立刻切、其他错误连续 5 次切、没网和飞行模式之类不算） | 原版只有一个定位源 | Key 配错、高德服务出问题时一直没有点 |
| **单次取点护栏** `BoundedLocationSource`：每次取点另起协程，最多「定位源时限 30 秒 + 10 秒」，到点取消、当没拿到 | 原版定位源自己设时限，引擎直接调 | 定位源有一处不理取消，引擎就拿着锁卡死（P20 11-18 一整夜不报，`docs/reports/2026-11-18-p20-zone-fix-hang.md`） |
| 高德单次取点每次新建客户端、用完销毁（不共用、不加锁） | — | 根目录旧 App 共用一个单次客户端加锁，锁被占着时后面的取点一直等 |
| 系统定位每次请求用自己的取消信号和时限 | 原版共用一个取消信号、停连续定位前取最后一点不设时限 | 后一个请求把前一个取消掉，被取消的不再回调，永远挂着 |
| 系统定位拿不到新点不再退回 `getLastKnownLocation` | 原版要「总能给个点」 | 心跳和「发送位置」可能把几小时前的位置当现在报（项目约定不许） |
| 系统定位要的定位方式没有时先退到 GPS、网络定位 | 原版取列表第一个 | 没有网络定位的手机（AOSP 模拟器实测）取到 passive，它自己不定位，高德切过来后一个点都没有 |
| **删** Fused、Google 活动识别、Google 地理围栏，去掉 `play-services-location` 依赖 | — | 国内用不了：没有 Google 服务时活动识别报 `ActivityRecognition.API is not available`、地理围栏报 1000；P20 装了 GMS 但 Fused 单次定位会挂住不回。地理围栏只在进了静止后注册，进静止只靠活动识别，留着也是死代码 |

- 高德 Key：打包时先读 `app-v2/android/local.properties` 的 `AMAP_KEY`，没有再读根目录旧 App 的 `android/local.properties`（同一个 Key）。都没有也能编，运行时日志 `No AMap key, using system location only`。设置里打开「使用系统定位」也只用系统定位。
- 连续定位：高精度 1 秒一个点；省电（只用 Wi-Fi 和基站）10 秒一个点；设置了按时间上报就按那个间隔。只是让原版的「一直连续定位」能用高德跑起来，什么时候开 GPS 由第三步定。
- 2026-11-18 在 gps-nogms 模拟器上验过（arm64 正式签名包，上报到本机假服务器）：没 Key 的包只用系统定位；有 Key 的包高德定位上报、坐标和模拟器给的 WGS-84 位置差 1 米内；「发送位置」高德报错后系统定位补上；省电模式下高德报错 8 连续 5 次后切系统定位、接着上报。日志和截图在 `docs/r2-emulator/`。

## 第三步：判断层（R3）
在不在动、开不开卫星、多久报一次，全由 `sdk/core` 里的判断层定（纯逻辑，JVM 单测和录制回放能直接跑）；原版靠 Google 活动识别和地理围栏，国内用不了。

| 模块 | 放哪 | 只负责 |
|---|---|---|
| `sense/` 传感器快照 | `commonMain/.../sense/`（快照、录制 CSV、窗口统计、走路节奏，从根目录 SDK 搬来）+ `androidMain/.../sense/SnapshotSampler.kt` | 2 秒一份快照：加速度、陀螺、朝向、累计步数、屏幕、充电、飞行模式、联网方式、Wi-Fi（BSSID、计费、网关） |
| `motion/` 运动判断 | `commonMain/.../motion/`（从根目录 SDK 搬来，含 P20 两份录制回放） | 快照 → 静止 / 屋里走动 / 走路 / 坐车 / 飞行模式 |
| `policy/` 策略 | `commonMain/.../policy/`：`WifiBook` Wi-Fi 辅助（上次卫星定位时连的 Wi-Fi、认热点）、`ReportPolicy` 开卫星和上报节奏、`Judge` 合起来并填上报字段 | 运动 + 收到的点 + 时间 → 开不开卫星、报哪个点 |
| 引擎 | `commonMain/.../TrackerEngine.kt` | 照判断层说的开关连续定位、取网络定位、交给原版的上报和离线队列 |

依赖只往下：引擎 → `policy/` → `motion/` → `sense/`；执行层（R2 的定位源、原版上报、离线队列）和保活（R4）不知道判断层。

每一样「原版为什么没有、不加会怎样」：

| 加 / 改 | 原版为什么没有 | 不加会怎样 |
|---|---|---|
| 传感器判断在不在动（`motion/`） | 原版用 Google 活动识别 | 国内手机进不了静止，一直连续定位费电；或者像旧 App 靠网络定位判断，断网时 2 小时认不出在动（P20 11-18 上午） |
| 按运动开关卫星、上报节奏（`ReportPolicy`）：走路 / 坐车马上开卫星 10 秒一个点，屋里 5 分钟一个网络点，静止 5 分钟呼吸，飞行模式全停 | 原版按设置的距离 / 间隔过滤连续定位（`LocationFilter`，删了），静止时按心跳间隔报 | 不动时也开着卫星；动起来要等位置证明才开 |
| 主坐标只放卫星点（方案 3a 定稿）：Wi-Fi、基站定位只报时间（服务器沿用上一个卫星位置），估算放 `netLat`、`netLon`、`netAcc`、`netSource` | 原版什么定位都放主坐标 | Traccar 的围栏、通知、轨迹按主坐标算，网络定位一偏就误报；P20 11-18 17:29 连上另一处 Wi-Fi 后高德报回 1.1 公里外的工作室，圆点跳回去 |
| 屋里一直没卫星时网络估算顶上主坐标（`NetFallback`）：10 分钟没报过卫星点（或从没有过主坐标），可信估算（误差 ≤200 m、不是热点和跟着人走过的 Wi-Fi 下的）隔 ≥60 s 两次一致、离上一个主坐标超过 300 m + 误差，就当主坐标报一次（`provider=network`，`accuracy` 是误差）；连着有卫星锚点的 Wi-Fi 时不顶。这个 Wi-Fi 还没有锚就拿估算当临时锚点，第一个开阔卫星点替换。每个上报点都带最近 10 分钟内的可信估算（`net*`）；上一个主坐标存在状态库（`State.main`） | — | P20 11-19 屋里卫星点都被挡档丢掉，主坐标停在 12 小时前 2.8 公里外；三星全新装从没有过主坐标，服务器记成 0,0 |
| Wi-Fi 只辅助状态（`WifiBook`）：还连着上次卫星定位时连的 Wi-Fi 且屋里走动就不开卫星；计费、172.20.10.x、名字像手机、跟着人走的算热点，当没连，连着热点时也不用高德的 Wi-Fi 定位 | 原版不看 Wi-Fi | 在家里走动也开卫星；连着热点出门被当成没离开 |
| 上报带 `state`、`motion`、`provider`、`steps`、`bumps`、`net`、`wifiSame`（还是不是上次卫星定位时那个 Wi-Fi）和上面的 `net*`；离线队列加 `extras` 一列存它们（`3.sqm`，库第 4 版） | 原版只报位置、电量 | 家长页不知道状态、联网方式 |
| 传感器批量上报（约 10 秒一批，有唤醒型加速度就用），静止和飞行模式时只挂显著运动这个硬件唤醒；屏幕、充电、飞行模式、网络都等系统事件；不拿长期唤醒锁 | — | 25 次/秒连续采要一直拿唤醒锁（测试用的录制就是这样），耗电，华为会提示后台高耗电 |
| 连续定位固定高精度 1 秒；停连续定位前不再取最后一点 | 原版按设置的精度和间隔、停前取一点 | 判断层要的节奏做不出来；转静止时报哪个点由判断层定 |
| 旧数据迁移只认第 4 版以上是旧 App 的库（旧 App 是第 7 版） | — | v2 自己升到第 4 版的库会被当成旧 App 的降回去 |

- 设置里的「定位精度」「距离」「间隔」「角度」「静止时上报间隔」「静止检测」在判断层下不起作用，第六步从设置页去掉了（存着的值还在，原样交给 SDK）。
- 验证：`cd sdk && ./gradlew :core:testAndroidHostTest`。P20 两份录制回放（`motion/MotionReplayTest`、`policy/JudgeReplayTest`）：
  11-17 夜到 11-18 中午夜里全是静止、开车 110 分钟、走路几秒判出；11-18 下午 14:30:52 判走路、14:33:25 判坐车（骑车）、
  等红灯 62 秒一直是坐车、14:16:56–14:27:20 飞行模式不报不开卫星、退出马上报、骑车时 10 秒一个卫星点、连着 iPhone 热点报 `net=hotspot`；
  `policy/JudgeTest` 用 17:29 那次（到新地方连上新 Wi-Fi，高德报回工作室）断言不报工作室坐标、状态不变 moving。
  模拟器（`tools/simulator`）的手机就是这套判断层的 Python 版，`policy/SimulatorGoldenTest` 逐行比对 6 个场景，一条不差。
- 没在真机上跑过：批量上报、显著运动唤醒、唤醒型传感器、网关读取都只在编译和单测层面验过；耗电和「后台频繁」提示要 P20 带一天看。

## 第四步：保活（R4）
原版的前台服务为底，加三样，都在 `sdk/core`：`commonMain/.../KeepAlive.kt`（规则，JVM 单测能跑）、`StuckWatch.kt`（连续卡住计数），`androidMain/.../BreathAlarm.kt`（闹钟、接收器、唤醒锁）、`BootReceiver.kt`。方向（主人定）：少唤醒、不让系统判后台频繁。

| 加 / 改 | 原版为什么没有 | 不加会怎样 |
|---|---|---|
| **呼吸闹钟，唯一的定时唤醒**（`KeepAlive` + `BreathAlarm`）：追踪开着就挂一个精确、打盹时也响的闹钟（`setExactAndAllowWhileIdle`），每产出一条上报就往后推到「这一条 + 5 分钟 + 2 秒」。在动时 10 秒一条，它永远不响；静止、屋里时 5 分钟响一次，叫醒判断层呼吸、取网络定位；5 分钟一条都没报（卡住、进程没了）它也会响，就是看门狗。替换原版的心跳闹钟（`AlarmHeartbeatTrigger`） | 原版靠 Google 活动识别叫醒、心跳闹钟用不精确的 `setAndAllowWhileIdle` 且默认不开 | 国内手机静止时 CPU 睡着，判断层没人叫；不精确闹钟打盹时被拖到 9–15 分钟以上，家长页按 15 分钟判失联会误报 |
| 闹钟挂在「上一条报完 5 分钟后」，不按状态各挂各的 | — | 屋里转静止时「卫星关着」没变，闹钟还是进屋时挂的，比判断层的呼吸到点早几十秒，白响一次，呼吸间隔变成 10 分钟（`KeepAliveTest` 第一条就是这个场景，撤掉往后推就失败） |
| 闹钟响时拿唤醒锁，等这次的取点、上传做完就放，最多 75 秒（网络定位最多 40 秒 + 上传）；不长期拿 | 原版心跳只把信号发出去就结束接收器 | 系统只在 `onReceive` 里拿锁，接收器一结束 CPU 就可能睡，呼吸点要等下一次醒才发 |
| 闹钟响时前台服务不在就拉起来（精确闹钟允许后台起前台服务）；进程是被闹钟叫起来的就按存着的配置恢复 | 原版靠 `START_STICKY` 等系统重启服务 | 服务被系统停了、进程被杀以后要等系统哪天重启它 |
| **看门狗**：呼吸 20 秒内引擎没接（锁被卡死的活占着）就 1 分钟后再看，连续 3 次重建跟踪；单次取点超过时限（`BoundedLocationSource`，30 + 10 秒）连续 3 次也重建。重建 = 按同样配置把定位源、引擎、保活全部新建，等于关开一次追踪，追踪开关、Wi-Fi 记忆、离线队列在库里接着用；等旧组件停最多 10 秒 | 原版假设定位源一定会回来，关开追踪只改开关、不重建组件 | P20 11-18 定位源卡住一整夜不报，关开追踪也救不回来（`../docs/reports/2026-11-18-p20-zone-fix-hang.md`），只能等人发现 |
| 改设置、重建时不停前台服务，只有关追踪才停；闹钟也只在关追踪时撤 | 原版改设置先停服务再起 | 看门狗在后台重建时再起前台服务可能被安卓 12+ 拦下，服务一停进程就可能被杀；闹钟撤了进程死了就没人叫回来 |
| 开机、更新后自启：原版就收 `BOOT_COMPLETED`、`MY_PACKAGE_REPLACED`，多收两个快速开机广播 `QUICKBOOT_POWERON`（部分国产机、HTC）；恢复完再写日志 | 原版面向标准安卓 | 部分国产机快速开机不发 `BOOT_COMPLETED`，不自启；原版在日志库打开前写日志，开机那条丢了 |
| 依赖图修正：引擎的作用域在 Koin 里写明 `ComponentCoroutineScope`（`CoreModule.kt`），加 `CoreModuleTest` 用真模块解析引擎和 Tracker | — | 第三步把引擎参数改成 `CoroutineScope` 后 Koin 解析不到，v2 开不了追踪、开机恢复全失败（单测都是手搭的引擎，没发现；这一步在模拟器上发现） |

- 精确闹钟权限：SDK 清单同时声明 `USE_EXACT_ALARM`（13+ 安装即授予）和 `SCHEDULE_EXACT_ALARM`（12 和不认前者的系统），和旧 App 一样（`../DECISIONS.md`「精确闹钟权限」）；拿不到就退回不精确闹钟并记日志。
- 飞行模式时也是 5 分钟响一次（不报点，只确认引擎活着），一小时 12 次，和静止一样。
- `WakeLockHolder`（原版「唤醒锁」设置，默认关）没动。
- 验证：`cd sdk && ./gradlew :core:testAndroidHostTest`。`KeepAliveTest`：静止时闹钟 5 分钟到点就呼吸一次（连续 3 次，屋里转静止也不白响）、上传完才放唤醒锁、在动时每条上报把闹钟往后推、呼吸连续 3 次没接住重建（中间接住一次重新数）、取点连续 3 次超时重建、重启后从库里读回「追踪开着」马上挂闹钟并报点、闹钟响时服务不在就拉起来、关追踪撤闹钟而重建不撤。`CoreModuleTest`：真模块解析得出引擎（撤掉修正就失败）。
- 2026-11-18 在 gps-nogms 模拟器（Android 16）上验过（debug 包，无高德 Key，上报到本机记录服务），日志和脚本在 `docs/r4-emulator/`：
  - **doze 强制深度打盹 12 分钟照样按时呼吸**：静止后用 `cmd sensorservice set-uid-state com.dodo258.tracker idle` 停掉给 App 的传感器数据（模拟器没有显著运动传感器，传感器一直开着会自己驱动呼吸；停掉才像真机 CPU 睡着时那样只剩闹钟），拔电、灭屏、`dumpsys deviceidle force-idle`，12 分钟里每分钟查一次都是 `IDLE`。21:43:59 转静止，21:49:01、21:54:03 两次 `Breath alarm fired` → `Report still breath` → 本机服务器收到（间隔 302 秒）；`dumpsys alarm` 里下一次是精确闹钟（`exactAllowReason=policy_permission`、`window=0`），排在 21:59:05。
  - **更新后自启**：追踪开着时 `adb install -r` 同一个包，1 秒内 `Boot received: MY_PACKAGE_REPLACED, tracking true`、`Service created`、挂闹钟，2 秒后报点。
  - **重启后自启**：`adb reboot`，开机完成 1 秒内 `Boot received: BOOT_COMPLETED, tracking true`、服务起来、报点（模拟器没设锁屏密码）。
  - 模拟器镜像里装的是 11-16 的旧 App（库第 4 版），覆盖装 v2 会崩（`no such column: extras`），这次先卸了旧 App 再装；问题已记进 BACKLOG「建议」。

## 第五步：告警（R5）
求救、关追踪（`trackingOff`）、低电（`lowBattery`）、关机（`powerOff`）、关定位（`general`）五种，`alarm` 值和文案沿用旧 App（家长页 `parent-web/src/domain/alarms.ts`、通知模板都认这几个）。
- **先存再发**（`sdk/core/.../alert/AlertOutbox.kt`）：告警先写进本机库的 Alert 表（`Alert.sq`，库升到第 5 版），服务器回 2xx 才记已报；断网、超时、非 2xx 都留着，联网就发、失败退避重试。进程没了也不丢：开机自启、打开 App、或者有没发出去的告警时挂的联网任务（`AlertRetryJob.kt`，JobScheduler，重启后还在）把进程叫起来补发。发送全进程一把锁，组件重建、联网任务同时发也不会发两次。求救先发，其余按先后；没发的超过 200 条丢最旧的，求救不丢。
- **位置只用新鲜的卫星点**（`alert/AlertRules.kt` 的 `freshGpsFix`）：30 秒内的卫星点才带，时间用定位时间；Wi-Fi、基站、旧点、时间对不上的都只报时间（审计 M4）。求救在引擎手里没有新鲜卫星点时先存一条只报时间的，再现取一次（最多约 40 秒），取到才换上。
- **求救界面**（`lib/sos.dart`）：首页按钮（先确认一次）和桌面快捷共用，盖在所有页面上，桌面快捷不用家长密码。发送中 / 已发给家长 / 还没发出去（联网后自动补发，可以再发或打电话）/ 出错写原因。界面记住这条求救的编号：「再发一次」只重发它；还没发出去时每 5 秒问一次，后台补发出去了就改成已发给家长（桌面快捷进来的 3 秒后退回桌面）。没发出去不退（审计 M3）。
- 关追踪：`Tracker.stop()` 开着时先记一条，所以首页开关、桌面快捷、`action/stop` 链接都会报。低电、关机、关定位只在追踪开着时盯（`AlertMonitor.kt`，门槛和「报过」的键名同旧 App，覆盖安装接着用）。
- 2026-11-18 gps-nogms 模拟器（debug 包，上报到本机假服务器）：飞行模式下桌面快捷求救 → 界面「还没发出去」，再点「再发一次」库里还是一条 → 关飞行模式 2 秒内假服务器收到一条 `alarm=sos`（带 4 秒后定到的卫星点和定位时间）→ 界面改成「已发给家长」后退回桌面；再求救一次是新的第二条，各收到一次。飞行模式下开着追踪把电量设成 9%、再从链接关追踪，联网后按顺序收到 `lowBattery`、`trackingOff`（都只报时间）。截图、假服务器收到的、SDK 日志在 `docs/r5-emulator/`。

## 第六步：界面和防护（R6）
从根目录旧 App 搬已实测的界面和交互（方向：沿用旧 App），搬之前按 `../docs/audits/2026-11-18-codex-app-owner.md` 修好。取舍记在 `../DECISIONS.md`「上报端 v2 第六步」。

| 模块 | 放哪 | 只负责 |
|---|---|---|
| 首页、设置页 | `lib/main_screen.dart`、`lib/settings_screen.dart` | 首页：新版本提示、清单提示、设备号（可复制）、上次成功上报（15 分钟没报出去标红）、开关、发送位置、求救、日志、设置、清单。设置：设备号、服务器、扫码；高级设置里清单、防卸载、技术选项（离线缓冲、唤醒锁、定位来源）、测试用录制；App 密码一直看得到 |
| App 密码 | `lib/app_lock.dart`、`lib/password_service.dart`、`lib/setup/pass*.dart` | 冷启动和回到前台先锁；改设置、开关追踪前从底部弹密码；5 次输错报 tampering（`reason=passwordFailures`）并退回桌面 |
| 自检清单 | `lib/setup/checklist*.dart`、`lib/setup/background_check.dart` + `android/.../BackgroundCheck.kt` | 全部自动判定，没有手动打勾，见下 |
| 厂商项的实际表现 | `sdk/core/.../health/BackgroundHealth.kt`（纯函数）、`androidMain/.../health/HealthRecorder.kt` | 呼吸闹钟挂上 / 响了、每次上报、开机广播时记一笔，给出待验证 / 已生效 / 没生效 |
| 防卸载 | `lib/setup/guard*.dart` + `android/.../Guard*.kt` | 只用设备管理员（不做无障碍防护，主人 11-18 定）；被停用报 tampering（`reason=adminDisabled`），走 SDK 告警发件箱 |
| 检查更新 | `lib/update/` + `android/.../UpdateChannel.kt` | 一天查一次 `latest.json`，下载核对 SHA-256，交给系统前原生再核对包名、签名证书、versionCode |
| 测试用录制 | `sdk/core/.../sense/SnapshotRecorder.kt` + `lib/setup/recorder_section.dart` | 自己起一个采样器，每 2 秒一行 CSV，只存本机 |

**自检清单**（主人 11-18：全部自动判定，不让家长手动打勾）：
- 系统能读到的（定位开关、始终允许、精确位置、身体活动、通知、电池不优化、不受限、省电模式、暂停闲置应用、精确闹钟；建议项 WLAN 扫描、省流量）每次回到 App 现读，开了自动打勾，没开给「去开启」直达设置页（打不开的退到应用详情页并写菜单路径）。防卸载（设备管理员）、App 密码在「防护」一组。
- 系统读不到的厂商项（按品牌列，同旧 App）按三样实际表现判：重启后自己恢复 → 自启动、关联启动；锁屏时呼吸准不准 → 后台活动、省电策略、多任务加锁；锁屏时能不能发出去 → 允许联网、锁屏后不断网。一开始「待验证」并写怎么验证，攒够证据变「已生效」或「没生效 + 去设置 / 菜单路径」；按了「去设置」「改好了」这一项回到待验证，系统升级后全部重来。判定规则见 DECISIONS。
- 打开追踪的门槛：系统项、防卸载、App 密码有 ✗ 就挡；厂商项不挡（要开着追踪才验证得了）。

**按审计修的**：M1 安装前核对包身份；M2 锁着时来的链接 / 快捷操作解锁后再确认这一个操作、离开作废；M8 验证时能输 17 位以上的旧数字密码；M17 正式包不认测试更新地址；M18 录制出错就停、显示原因、有大小上限；S3 加换机传输排除规则；S4 扫码只认配置码。密码 5 次输错的告警改走 SDK 发件箱（没网也不丢）。M3、M4、M5 在第五步已修；S1、S2、M6、M7、M9 是三星版和发布脚本的事（R7）。

- 验证：`flutter test`（124 条，含 M2、M8 撤掉修复就失败的测试，清单模型 / 清单页 / 开追踪门槛、设置页、更新身份不对不装）、`cd sdk && ./gradlew :core:testAndroidHostTest`（`health/BackgroundHealthTest` 15 条、`AlertOutboxTest` tampering 带 reason 一条）。
- 2026-11-18 gps-nogms 模拟器（debug 包，上报到本机假服务器），截图在 `docs/r6-emulator/`：
  - 清单：系统项现读（缺电池、闲置应用两项时 ✗ + 去开启 / 去设置，开了回来自动 ✓）、厂商项待验证；设 App 密码、激活设备管理员（回到 App 自动打勾）、启动密码页、改设置的密码框、设置页高级设置。
  - 开着追踪 `adb reboot`，开机广播 22 秒后接上追踪，不打开 App，清单「自启动」变「已生效」。
  - 锁屏、停掉给 App 的传感器数据、拔电、`deviceidle force-idle`：三次呼吸闹钟间隔 302 秒都准点、三次灭屏上报都发出去，「多任务加锁」变「已生效」，清单「全部完成」。
  - 锁着时发 `action/stop` 链接：解锁后弹「要继续吗？停止追踪（家长会收到提醒）」，取消后追踪还开着（M2）。
  - 检查更新（`--dart-define=UPDATE_URL` 指到本机假 latest.json）：同签名 versionCode 306 的包弹出系统安装框；正式签名的包对装着的 debug 包被拦下（日志 `Update apk rejected: signature`），删包、提示没有安装（M1）。
  - 测试用录制：每 2 秒一行，屏幕、充电、联网、Wi-Fi、网关都有值。
- 模拟器上发现并修了三处：对的更新包也被当成 unreadable 拦下（`?.let(verify) ?: "unreadable"`）；`SnapshotSampler` 的系统状态（屏幕、充电、飞行模式、联网、Wi-Fi、步数）从第三步起一直是空的（扩展函数里 `copy(screen = screen)` 取到了快照自己的字段），判断层看不到飞行模式和 Wi-Fi，上报一直 `net=none`；`SnapshotSampler` 冷启动时监听器还没初始化就注册、把进程带崩。

## 没解锁也恢复上报（Direct Boot）
设了锁屏密码的手机开机后、孩子第一次解锁前，凭据存储（App 平常的数据目录）读不了。原来 SDK 的库都在那里，要等孩子解锁才恢复上报（旧 App 在 P20 上实测解锁后约 80 秒）；夜里系统更新重启、没电关机后充电自己开机、死机重启，孩子没解锁前家长一直看不到位置。现在开机后十几秒就接着报。

| 改了什么 | 放哪 | 为什么 |
|---|---|---|
| SDK 的东西全放**设备保护存储**：`tracker.db`（配置里的服务器地址和设备号、追踪开关和运动状态、Wi-Fi 记忆、离线队列、待发告警、日志）和两个 SharedPreferences（告警「这一轮报过了」、自检清单的实际表现） | `sdk/core/.../DeviceStorage.kt`、`PlatformModule.kt` | 没解锁时只有它能读写。App 密码、防卸载记录、Flutter 的设置仍只在凭据存储 |
| 之前的版本放在凭据存储：解锁着时进程一起来就搬（同一分区改名，带 `-journal`、`-wal`）；两边都有库时逐行合并：离线点、告警、日志按内容去重（重复跑不多一条），配置和状态用设备保护存储里的（更新的）；偏好只补没有的键 | `DeviceStorage.migrate`、`commonMain/.../StorageMerge.kt` | 升级不丢离线点和待发告警，也不重发 |
| 启动、前台服务、开机广播、呼吸闹钟、告警补发任务标 `directBootAware`；多收 `LOCKED_BOOT_COMPLETED` | `sdk/core/src/androidMain/AndroidManifest.xml`、`BootReceiver.kt` | 没解锁时系统只拉起标了的组件、只发这个广播；它和 `BOOT_COMPLETED` 一样允许在后台起前台服务 |
| 没解锁时**只用系统定位**；解锁那一刻（`ACTION_USER_UNLOCKED`，或者先到的 `BOOT_COMPLETED`）先把凭据存储里剩的并过来，再按同样配置重建成完整的一套（用高德；重建不停前台服务） | `PlatformModule.kt`、`UserUnlock.kt` | 高德 SDK 自己用 SharedPreferences、SQLite 和文件（location 11.3.101 的 jar 里各有 11、5、10 个类），都在凭据存储，没解锁时读写会抛异常、可能把进程带崩 |
| 没解锁时报的点（含告警）带 `locked=true`，存进离线队列也带着；家长页状态句写「手机刚重启」（灰色，失联仍优先） | `AndroidBatteryProcessor.kt`、`Position.kt`、`HttpUploader.kt`、`DatabaseQueue.kt`；`parent-web/src/domain/status.ts` | 家长知道为什么这会儿位置不准（屋里多半只报时间） |
| App 自己碰凭据存储的启动工作（防卸载补报、测试用录制）等解锁后再做 | `android/.../TrackerApplication.kt`（`DeviceStorage.whenUnlocked`） | 开机广播在没解锁时把进程叫起来，那时碰凭据存储会抛异常 |
| 进程刚起来、日志库还没打开时写的日志先攒着（最多 100 条），打开后按原来的时间补写 | `commonMain/.../Log.kt` | 原版直接丢掉；搬存储、开机广播那几条正是要看的 |
| 换机传输、云备份也排除设备保护存储里的库和设置（`device_database`、`device_sharedpref`） | `android/app/src/main/res/xml/` | 审计 S3：带走设备号会让两部手机报同一个设备 |

- 旧 App 留下的库（第 7 版）等 App 的 `LegacyDataMigration` 降好版本再搬：它不是 directBootAware，解锁后才跑；SDK 见到比自己新的库版本就先不动，`BOOT_COMPLETED` 时再搬。
- 验证：`cd sdk && ./gradlew :core:testAndroidHostTest`（`StorageMergeTest`：离线点、告警、日志并过来不重复，并两遍也一样；配置和状态用新的；发过的告警不再发。`TrackerEngineTest.queueKeepsJudgementFields` 断言离线队列存得下 `locked`）；家长页 `npm test`（`status.test.ts`「手机刚重启」、`parse.test.ts` 只认 `locked: true`）。
- 2026-11-19 gps-nogms 模拟器（Android 16，debug 包，无高德 Key，上报到本机记录服务），脚本和输出在 `docs/direct-boot-emulator/`：
  - `reboot-locked.sh`：开着追踪、凭据存储里放一份「升级前的老库」（`plant.sql`：一个离线点、一条没发的低电告警），设锁屏密码 1234，`adb reboot`，不解锁。00:11:00 重启，00:11:11 `Boot received: LOCKED_BOOT_COMPLETED, tracking true, unlocked false`、`Device locked since boot, using system location until unlocked`、服务起来、挂呼吸闹钟，**00:11:13 本机服务器收到第一条（开机后 13 秒，用户状态 `RUNNING_LOCKED`）**，之后 00:13:13 转静止、00:18:15 呼吸闹钟准点响（302 秒），4 条都带 `locked=true`。00:22 解锁：同一秒 `Device storage: tracker.db merged` → `Rebuilding tracker: user unlocked` → 老库的离线点（31.2304, 121.4737）和低电告警各发出去一次，之后的点不再带 `locked`，凭据存储里的老库删掉了，离线队列清空。
  - 最后一版包（加了搬存储排队）再跑一次（`reboot-locked-final-build.txt`，凭据存储里没放老库）：重启后 12 秒、没解锁就收到第一条，解锁后重建。
  - `upgrade-move.sh`：只有凭据存储里有库和偏好（之前版本升上来的样子）时打开 App：`preferences background_health moved`、`tracker.db moved`，追踪接着开着、接着报。

## 第七步：可靠性自查、故障注入、加速压测
按方案「可靠性要求」8 条逐条自查，结论和证据见 `docs/reliability-audit.md`。补上的：

| 补了什么 | 放哪 | 不补会怎样 |
|---|---|---|
| 组件作用域加异常处理：没接住的异常记一条、交看门狗 | `Component.kt` | 存储满时写库、写日志抛的异常落到线程默认处理，Android 上进程直接崩，重启后再写还崩 |
| 写日志失败只计数、恢复后补一句 | `Log.kt` | 同上 |
| 离线队列外加一层：库写不进先放内存（2000 条），库好了按顺序补；发出去删不掉不重发 | `ResilientQueue.kt` | 上报循环一次写库失败就停了，之后一个点都不报 |
| 离线队列上限 5 万条，满了丢最旧的 | `DatabaseQueue.kt` | 长期没网时把存储吃满 |
| 上传总时限（HTTP 连接 15 / 读写 20 / 整次 30 秒，引擎再兜 40 秒）；系统说没网最多等 5 分钟就试一次 | `PlatformModule.kt`、`TrackerEngine.kt`、`AlertOutbox.kt` | 服务器收了不回时发送挂住；联网回调漏了就一直不发 |
| 时间被改：引擎拿开机时长比出改了多少，判断层记的时间整体挪过去 | `TrackerEngine.clock`、`ReportPolicy.shift` | 往回改一天就一天不报 |
| 一份传感器快照都没有时，第一次呼吸起按「在附近活动」报 | `Judge.onTick` | 没有加速度计的手机一个点都不报 |
| 重启后接着静止 | `State.motion`、`Judge(restored)` | 被杀、重启后先报一条「在附近活动」、开传感器 |
| 看门狗：加「多久没上报」（10 分 30 秒，开机时长计），按次升级 重建定位源 → 重建整套 → 报 `alarm=fault` 并重建 | `KeepAlive.check`、`Watchdog.kt`、`Tracker.rescueSharedTracker` | 判断层卡在某个状态、循环停了没人发现；每次都整套重建 |
| 降级、重建、丢弃、写不进存储记 `Fault …` 日志，自检页「运行记录」写最近一次 | `Log.fault`、`HealthRecorder`、`lib/setup/checklist_screen.dart` | 出了事只能翻日志 |

- 故障注入：`sdk/core/src/androidHostTest/.../FaultInjectionTest.kt` 覆盖第 6 条全部 12 种，每种断言服务器按顺序、不丢不重收到全部点；`ReliabilityPartsTest`、`WatchdogTest` 测零件。
- 加速压测：`../tools/stability/v2_stress.sh`（说明在 `../tools/stability/v2_stress.py` 开头），结论在 `../docs/stability-report.md`「上报端 v2 加速压测」。
- `alarm=fault` 家长页现在显示成「告警（fault）」，服务器通知模板也没有这一类：要中文文案得另做（不在这一步范围）。

## 和旧 App 的关系
- 根目录的旧 App（10.2.x）不动，P20 继续用它，直到 v2 在 P20 上跑通再换。`parent-web/`、`server/`、`tools/`、`docs/` 两边共用，不复制。
- 两个 App 包名和签名一样，v2 能直接覆盖安装旧 App（`adb install -r`），反过来不行（versionCode 更小）。
- 覆盖安装后接着用的本机数据：
  - **设备号、服务器、上报间隔、App 密码**：两边存在同一份 Flutter SharedPreferences、同一个键，原样读到。旧 App 存的 App 密码是 PBKDF2 加盐哈希，v2 带了同一份 `lib/password_hash.dart` 来验证；原版存的明文启动时也换成哈希。测试：`test/legacy_data_test.dart`。
  - **SDK 的 tracker.db**：旧 App 的改版 SDK 把库升到了第 7 版，配置、状态 JSON 里有原版不认识的字段，原版 SDK 直接打开会崩。`android/app/.../LegacyDataMigration.kt`（ContentProvider，进程启动时在 SDK 之前跑）用 `LegacyTrackerDb.kt` 把它原地迁成 v2 SDK 的第 5 版（之后 SDK 自己往上迁；认旧库看 Position 表有没有 `alarm` 列，旧 App 第 4 版起就有、v2 永远没有；不按版本号划界，v2 加表升版本不用跟着改）：离线队列里的卫星点留主坐标、网络定位点估算挪进 extras、来源不明的只留时间；没发出去的告警转进 Alert 表（类型不丢）；配置只留原版有的字段、状态只留「是否在追踪」（覆盖安装前开着追踪的，装完自己接着追踪）。迁移在一个事务里，失败就把旧库改名挪到 `databases/legacy-backup/tracker.db.<时间>` 留着（不进云备份）、SDK 建新库（改名也失败才删）。测试：`android/app/src/test/.../LegacyTrackerDbTest.kt`（真 SQLite）。
  - 旧 App 独有的数据：防卸载开过没有（原生「guard」的 `want_admin`）、App 密码输错次数、更新检查记录、打开过清单的系统指纹键名都一样，覆盖安装接着用；手动打勾记录、重启自测、无障碍防护不再用；三星版设备所有者在 R7。
- 2026-11-18 在 gps-nogms 模拟器上验过：装旧 App 10.2.23（187）→ 开追踪、断网攒离线点、设 App 密码 → 覆盖装 v2 → 设备号不变、追踪自己恢复、旧密码能进错密码进不去、日志页有 `Legacy tracker.db migrated from version 7 to 3`。截图在 `docs/overwrite-install/`。

## 怎么构建（都在 app-v2/ 里跑）
```sh
cd app-v2
flutter pub get
flutter analyze --no-fatal-infos     # 只剩上游原有的 deprecated 提示
flutter test                         # Dart 测试
(cd sdk && ./gradlew :core:testAndroidHostTest)  # SDK 单元测试（JVM 上跑：坐标转换、高德切系统定位、取点护栏、判断层和 P20 录制回放、和模拟器的金样本、告警发件箱）
flutter build apk --debug --config-only && (cd android && ./gradlew :app:testDebugUnitTest)   # 原生单测（旧数据迁移）
flutter build apk --release --target-platform android-arm64   # 正式包：build/app/outputs/flutter-apk/app-release.apk
$ANDROID_HOME/build-tools/<版本>/apksigner verify --print-certs build/app/outputs/flutter-apk/app-release.apk   # SHA-1 应是 6f78a5dc…
```
仓库的 `.agentq/verify` 也会跑上面的检查。

### 检查：根目录的 analyze 也会扫 app-v2
根目录的 `flutter analyze` 会把 app-v2 一起扫进去，那时 app-v2 可能还没 `pub get`：没有自己的包配置就借用根目录的，`package:traccar_client` 会指到旧 App 的 lib（两个包同名）。所以 app-v2 守两条：
- 引用自己的代码一律用相对路径（`lib/` 里互相引用，`test/` 用 `../lib/`），不写 `package:traccar_client/`；
- gen-l10n 的产物 `lib/l10n/app_localizations*.dart` 提交进仓库；改了 ARB 在 app-v2 里跑 `flutter gen-l10n`（或 analyze/build，会自动重新生成）再一起提交。

## 现在的限制（后面几步做）
- 三星版（R7）还没有；三星管理版的 tampering 告警（审计 M5）在 R7 接进同一个发件箱。没解锁也恢复上报都在 SDK 和 `android/app/src/main` 里，R7 加 flavor 后两个版本都带着（R7 要核对：owner 的清单里新加的组件碰凭据存储就要么等解锁、要么也标 directBootAware）。
- 没解锁时只用系统定位：没有网络定位的手机在屋里只报时间（家长页「手机刚重启」）；解锁后换回高德。Direct Boot 只在模拟器上验过，P20（鸿蒙 3.0）和三星要装上实测重启不解锁。
- 自检清单的厂商项判定只在模拟器（「其他」品牌：自启动、多任务加锁）上验过，各品牌真机要装 P20 看。
- 判断层没在真机上跑过（见上一节最后一条）；阈值按 P20 一台定，三星到了要再录一次核对。
- 新装时设备号是原版的随机 8 位数，不是旧 App 按 ANDROID_ID 算的（卸载重装会变号）；覆盖安装不受影响。
- iOS 不管，`ios/` 保持原版（还引用着 Firebase 的配置文件），不保证能编。
