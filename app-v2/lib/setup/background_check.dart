import 'package:flutter/services.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_android/shared_preferences_android.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

import '../app_lock.dart';
import '../geolocation_service.dart';

/// 系统能读到状态的项，顺序即清单里的显示顺序；[suggestion] 的只是建议，放「建议」一组。
enum CheckItem {
  locationService,
  backgroundLocation,
  preciseLocation,
  motionPermission,
  notifications,
  batteryOptimization,
  backgroundRestriction,
  powerSave,
  hibernation,
  exactAlarm,
  wifiScan,
  dataSaver;

  /// 不算进「还差 N 项」、不卡追踪开关：WLAN 扫描只影响室内精度；省流量原生安卓不限前台服务，国产系统没实测。
  bool get suggestion => this == wifiScan || this == dataSaver;
}

/// 系统读不到、按 App 的实际表现判定的厂商设置。名字和原生侧 openVendorSettings 的参数一致。
enum VendorItem {
  autostart,
  associatedLaunch,
  backgroundActivity,
  powerSaving,
  networkAccess,
  screenOffNetwork,
  recentsLock;

  /// 没有可靠的设置页可跳：不给「去设置」，菜单路径直接写在这一行。
  bool get pathOnly => this == networkAccess || this == screenOffNetwork || this == recentsLock;

  /// 用哪样实际表现判它：自启动看重启后自己恢复没有；后台、省电、多任务加锁看锁屏时呼吸准不准；联网看锁屏时发不发得出去。
  HealthEvidence get evidence => switch (this) {
        autostart || associatedLaunch => HealthEvidence.reboot,
        backgroundActivity || powerSaving || recentsLock => HealthEvidence.breath,
        networkAccess || screenOffNetwork => HealthEvidence.send,
      };
}

/// na：这台手机的系统版本没有这项，或本 App 用不到。
enum CheckState { ok, bad, na }

class BackgroundStatus {
  const BackgroundStatus({
    required this.items,
    required this.brand,
    required this.fingerprint,
    required this.device,
    required this.rom,
    required this.release,
  });

  factory BackgroundStatus.fromMap(Map<dynamic, dynamic> map) {
    final raw = map['items'] as Map<dynamic, dynamic>;
    return BackgroundStatus(
      items: {
        for (final item in CheckItem.values) item: CheckState.values.byName(raw[item.name] as String),
      },
      brand: map['brand'] as String,
      fingerprint: map['fingerprint'] as String,
      device: map['device'] as String,
      rom: map['rom'] as String?,
      release: map['release'] as String,
    );
  }

  final Map<CheckItem, CheckState> items;

  /// 原生侧按 Build.MANUFACTURER 归好的品牌：xiaomi、huawei、honor、oppo、vivo、samsung、other。
  final String brand;
  final String fingerprint;

  /// 商品名（如「Redmi K70」），读不到时是「品牌 型号」。
  final String device;

  /// 厂商系统版本（如「HyperOS OS2.0」），读不到为 null。
  final String? rom;

  /// 安卓版本号（Build.VERSION.RELEASE）。
  final String release;

  /// 问 AI 时用的「品牌 型号 系统版本」。
  String get deviceLabel => [device, if (rom case final rom?) rom, 'Android $release'].join(' / ');
}

class BackgroundCheck {
  static const _channel = MethodChannel('com.dodo258.tracker/background_check');
  static const _fingerprintKey = 'background_check_fingerprint';

  // 和 Preferences 用同一个 Android 存储后端；键名和根目录旧 App 一样
  static final _store = SharedPreferencesAsync(
    options: SharedPreferencesAsyncAndroidOptions(backend: SharedPreferencesAndroidBackendLibrary.SharedPreferences),
  );

  /// 原生侧读系统设置，每次都现读。
  static Future<BackgroundStatus> status() async =>
      BackgroundStatus.fromMap((await _channel.invokeMapMethod<dynamic, dynamic>('getStatus'))!);

  /// 厂商设置的实际表现（SDK 记的）；读不到就当都还在等。
  static Future<Map<HealthEvidence, HealthJudgement>> health() async {
    try {
      return await GeolocationService.tracker.getHealth();
    } on PlatformException {
      return {for (final e in HealthEvidence.values) e: const HealthJudgement(HealthVerdict.pending)};
    }
  }

  /// 最近一次异常（SDK 记的）；没有或读不到是 null。
  static Future<LastFault?> lastFault() async {
    try {
      return await GeolocationService.tracker.getLastFault();
    } on PlatformException {
      return null;
    }
  }

  /// 家长去改了这项设置：重新验证。
  static Future<void> resetHealth(HealthEvidence evidence) => GeolocationService.tracker.resetHealth(evidence);

  /// 返回 false 表示这项没有专门页面或打不开，退回了应用详情页。
  static Future<bool> openSettings(CheckItem item) async {
    appLock.expectExternalPage();
    return await _channel.invokeMethod<bool>('openSettings', item.name) ?? false;
  }

  /// 返回 false 表示没打开厂商页，退回了应用详情页。
  static Future<bool> openVendorSettings(VendorItem item) async {
    appLock.expectExternalPage();
    return await _channel.invokeMethod<bool>('openVendorSettings', item.name) ?? false;
  }

  static Future<String?> savedFingerprint() => _store.getString(_fingerprintKey);

  static Future<void> saveFingerprint(String fingerprint) => _store.setString(_fingerprintKey, fingerprint);
}
