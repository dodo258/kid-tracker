import 'dart:io';
import 'dart:math';

import 'package:flutter/widgets.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_android/shared_preferences_android.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

import 'l10n/app_localizations.dart';

class Preferences {
  static Future<void>? _initFuture;
  static late SharedPreferencesWithCache instance;

  static const String id = 'id';
  static const String url = 'url';
  static const String accuracy = 'accuracy';
  static const String distance = 'distance';
  static const String interval = 'interval';
  static const String angle = 'angle';
  static const String heartbeat = 'heartbeat';
  static const String buffer = 'buffer';
  static const String wakelock = 'wakelock';
  static const String stopDetection = 'stop_detection';
  static const String preferPlatformProviders = 'prefer_platform_providers';
  static const String password = 'password';
  static const String passwordFailures = 'password_failures';
  static const String updateCheckedAt = 'update_checked_at';
  static const String updateInfo = 'update_info';

  static Future<void> init() async {
    _initFuture ??= _createInstance();
    await _initFuture;
  }

  /// 测试里模拟覆盖安装：下次 [init] 重新读存储。
  @visibleForTesting
  static void resetForTest() {
    _initFuture = null;
  }

  static Future<void> _createInstance() async {
    instance = await SharedPreferencesWithCache.create(
      sharedPreferencesOptions: Platform.isAndroid
          ? SharedPreferencesAsyncAndroidOptions(backend: SharedPreferencesAndroidBackendLibrary.SharedPreferences)
          : SharedPreferencesOptions(),
      cacheOptions: SharedPreferencesWithCacheOptions(
        allowList: {
          id, url, accuracy, distance, interval, angle, heartbeat, buffer, wakelock, stopDetection, preferPlatformProviders, password,
          passwordFailures, updateCheckedAt, updateInfo,
        },
      ),
    );
    if (Platform.isAndroid) {
      for (final key in {interval, distance, angle, heartbeat}) {
        if (instance.get(key) is String) {
          await instance.setInt(key, int.tryParse(instance.getString(key) ?? '') ?? 0);
        }
      }
    }
    if (instance.getString(id) == null) {
      await instance.setString(id, (Random().nextInt(90000000) + 10000000).toString());
      await instance.setString(url, 'https://gps.example.com/osmand');
      await instance.setString(accuracy, 'medium');
      await instance.setInt(interval, 300);
      await instance.setInt(distance, 75);
      await instance.setBool(buffer, true);
      await instance.setBool(stopDetection, true);
    }
  }

  static Config buildConfig() {
    return Config(
      serverUrl: instance.getString(url) ?? '',
      deviceId: instance.getString(id) ?? '',
      location: LocationConfig(
        accuracy: switch (instance.getString(accuracy)) {
          'highest' => Accuracy.highest,
          'high' => Accuracy.high,
          'low' => Accuracy.low,
          _ => Accuracy.medium,
        },
        distanceMeters: instance.getInt(distance) ?? 75,
        intervalSeconds: instance.getInt(interval) ?? 300,
        angleDegrees: instance.getInt(angle) ?? 0,
        heartbeatIntervalSeconds: instance.getInt(heartbeat) ?? 0,
        stopDetection: instance.getBool(stopDetection) ?? true,
      ),
      wakeLock: instance.getBool(wakelock) ?? false,
      buffer: instance.getBool(buffer) ?? true,
      preferPlatformProviders: instance.getBool(preferPlatformProviders) ?? false,
      notification: NotificationConfig(
        text: localizationsFor(WidgetsBinding.instance.platformDispatcher.locales).notificationText,
      ),
    );
  }

  // 前台通知由原生服务显示，拿不到 BuildContext：按系统语言挑和 MaterialApp 相同的 ARB 文案
  static AppLocalizations localizationsFor(List<Locale> locales) {
    return lookupAppLocalizations(basicLocaleListResolution(locales, AppLocalizations.supportedLocales));
  }
}
