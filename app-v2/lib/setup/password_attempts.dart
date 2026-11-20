import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../password_hash.dart';
import '../preferences.dart';
import '../geolocation_service.dart';

enum PasswordCheck { ok, wrong, lockedOut }

/// 输 App 密码的次数：启动密码页和改设置的密码弹窗共用一个计数，存在设置里，App 重启后还在。
/// 输对清零；连续输错 [maxFailures] 次就执行 [onLockedOut]（报警并退回桌面），计数清零重新开始。
class PasswordAttempts {
  PasswordAttempts({required this.password, required this.onLockedOut});

  static const maxFailures = 5;

  final String? Function() password;
  final Future<void> Function() onLockedOut;

  int get failures => Preferences.instance.getInt(Preferences.passwordFailures) ?? 0;

  int get remaining => maxFailures - failures;

  Future<PasswordCheck> check(String input) async {
    if (PasswordHash.matches(password(), input)) {
      await reset();
      return PasswordCheck.ok;
    }
    final count = failures + 1;
    if (count < maxFailures) {
      await Preferences.instance.setInt(Preferences.passwordFailures, count);
      return PasswordCheck.wrong;
    }
    await reset();
    // 不等它：界面先显示「即将退出」，上报完才退
    unawaited(onLockedOut());
    return PasswordCheck.lockedOut;
  }

  Future<void> reset() async {
    if (failures != 0) await Preferences.instance.remove(Preferences.passwordFailures);
  }
}

/// 连续输错 5 次：报 alarm=tampering（reason=passwordFailures），再退回桌面（结束 Activity，下次打开是冷启动，先锁住）。
/// 告警交给 SDK 的发件箱：先存在手机上再发，没网时联网补发；存不进去（还没配置）也照样退出。
Future<void> lockOutApp() async {
  try {
    await GeolocationService.tracker.reportTampering('passwordFailures').timeout(const Duration(seconds: 5));
  } catch (e) {
    debugPrint('Alarm tampering/passwordFailures not stored: $e');
  }
  await SystemNavigator.pop();
}

final passwordAttempts = PasswordAttempts(
  password: () => Preferences.instance.getString(Preferences.password),
  onLockedOut: lockOutApp,
);
