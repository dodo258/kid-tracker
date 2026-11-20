# 孩子在哪：基于 Traccar 的孩子手机定位（安卓上报端 + 家长网页）

给自家孩子用的手机定位：孩子的安卓手机装一个上报 App，位置发到自己搭的 [Traccar](https://github.com/traccar/traccar) 服务器，家长用手机浏览器打开一个极简网页，看孩子在哪、多久前的位置、还剩多少电。

这是一个个人项目，2026 年 11 月做了大约一周，**已停止开发**，代码原样公开，供做同类东西的人参考。

## 结论先说

做到最后卡在一件事上：**普通手机上的第三方 App，没法保证一直在后台跑、一直能把位置发出去。**

- App 本身可以做得很稳：两台真机（华为 P20、三星 A57）连续跑 13 个多小时没被杀，断网时位置存在手机里，网络一回来几秒内全部补传。
- 但手机系统自带的省电机制会绕过 App 直接动手。实测例子：三星的「睡眠待机优化」（系统服务 `IntelligentBatterySaverService`）在夜里 0:01–7:02、手机灭屏又静止时，会一次关掉定位、WLAN、移动数据和蓝牙，到早上再打开。这期间 App 还活着，但什么也发不出去。
- 各家厂商的省电机制还在不断变，第三方 App 只能一个个发现、一个个应对，给不了「绝对」。

如果你要的是「孩子任何时候都能定位到」，更稳的办法是买专门的 4G 定位器（很多支持把服务器改成自己的 Traccar），这个仓库里的家长网页和服务器配置可以直接接上。

## 里面有什么

| 目录 | 是什么 | 技术 |
|---|---|---|
| `app-v2/` | 孩子手机上的上报 App（只做了安卓，iOS 没适配） | Flutter + Kotlin Multiplatform，基于 [Traccar Client](https://github.com/traccar/traccar-client) 和 [Traccar Client SDK](https://github.com/traccar/traccar-client-sdk) 改的 |
| `parent-web/` | 家长用的极简网页（PWA），直接调用 Traccar 的接口 | React + TypeScript + Vite，高德地图 |
| `server/` | Traccar 服务器的部署、备份、清理、健康检查脚本，通知模板，Telegram 通知中转（Cloudflare Worker） | Bash、Python、JavaScript |
| `tools/simulator/` | 上报端模拟器：用场景文件（走路、坐车、隧道、断网、进程被杀……）离线回放判断逻辑，和 App 对照 | Python |
| `tools/stability/` | 在安卓模拟器上压测上报 App 的脚本 | Python、Bash |

### 上报 App 相对原版改了什么

- **先感受再定位**：用加速度、计步、显著运动传感器判断孩子是静止、在附近走动还是在路上，静止时不开卫星，大幅省电；动起来再开。
- **屋里定位**：屋里收不到卫星时，用网络定位（高德）估算，并用「连着的 Wi-Fi 以前卫星定到的位置」顶上；识别跟着人移动的 Wi-Fi（手机热点、车载 Wi-Fi），不把它当成固定位置。
- **保活清单**：针对华为、小米、OPPO、vivo、三星等国产系统，逐项检查自启动、电池优化、后台限制、休眠应用等设置，没设好不让开始。
- **断网缓存**：没网时位置存进本地数据库（有上限，不会无限增长），联网后按顺序补传。
- **告警**：求救按钮、定位被关、权限被收回、低电，都会报给服务器；服务器按 Traccar 的规则发通知。
- **防卸载**：可选开启设备管理员，孩子卸载不了。
- 去掉了原版的 Firebase 推送、统计和崩溃上报。

详细设计见 `app-v2/README.md`。里面提到的 `docs/specs/...` 等内部设计文档没有一起公开。

## 怎么跑起来

### 1. Traccar 服务器

按 [Traccar 官方文档](https://www.traccar.org/install-vps/) 装好服务器（我们用的是 6.17），上报走 OsmAnd 协议（默认 5055 端口，或经反向代理映射到 `https://你的域名/osmand`）。`server/` 里的脚本是我们自己服务器的部署方式，里面的地址和钥匙名都换成了示例值，用之前要改成你自己的。

### 2. 上报 App（`app-v2/`）

- 去[高德开放平台](https://lbs.amap.com/)申请安卓定位 Key（绑定包名和签名），写进 `app-v2/android/local.properties`：`AMAP_KEY=你的Key`。这个文件不进 git。
- 默认服务器地址在 `app-v2/lib/preferences.dart`、自动更新地址在 `app-v2/lib/update/app_update.dart`，现在是 `gps.example.com`，改成你自己的。
- 包名是 `com.dodo258.tracker`，自己发布建议改掉（高德 Key 绑包名）。
- 构建：

```shell
cd app-v2
flutter pub get
flutter build apk --release --target-platform android-arm64
```

正式签名读仓库外的 `~/.config/gps-tracker/key.properties`，没有时退回 debug 签名。

### 3. 家长网页（`parent-web/`）

```shell
cd parent-web
npm ci
npm test
npm run build
```

把 `dist/` 部署到和 Traccar 同域名的 `/m/` 下（接口走同域名的 `/api/`）。具体见 `parent-web/README.md`。

## 已知限制

- 只适配了安卓，iOS 代码来自原版但没测过。原版 iOS 工程引用的 `ios/Runner/GoogleService-Info.plist`（原版自己的 Firebase 配置）没有放进来，要编 iOS 版得换成你自己的。
- 后台保活受厂商系统限制，见上面的「结论」。
- 国内地图坐标（GCJ-02）和卫星坐标（WGS-84）的转换在上报端和网页两边都有实现，改一边要同步改另一边。
- 测试和模拟器里的坐标都是假数据（杭州一带），真实轨迹已经整体平移过。

## 许可证

[Apache License 2.0](LICENSE)，和上游 Traccar Client 一致。上游版权和改动说明见 [NOTICE](NOTICE)。

---

**English summary**: A personal child-location project built on Traccar: a modified Traccar Client for Android (sensor-first motion detection, indoor network fallback, vendor-specific keep-alive checklist for Chinese Android phones, offline buffering) and a minimal parent web app on the Traccar API. Development has stopped. Main lesson: on stock Android phones, a third-party app cannot guarantee always-on reporting, because vendor power-saving features (e.g. Samsung's sleep standby optimization turning off location and all radios at night) act outside the app's control. For guaranteed tracking, use a dedicated 4G GPS tracker pointed at your own Traccar server. Licensed under Apache-2.0.
