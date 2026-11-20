import 'package:flutter/services.dart';

import '../app_lock.dart';

/// 防卸载（设备管理员）的状态。只用设备管理员，不做无障碍防护（主人 11-18 定）。
class GuardState {
  const GuardState({required this.admin, required this.wantAdmin});

  factory GuardState.fromMap(Map<dynamic, dynamic> map) =>
      GuardState(admin: map['admin'] as bool, wantAdmin: map['wantAdmin'] as bool);

  /// 系统里现在是否开着。
  final bool admin;

  /// 家长开过、还没用 App 密码关掉：开着却发现系统里没了，就是被停用了（已经报给家长）。
  final bool wantAdmin;

  bool get lost => wantAdmin && !admin;
}

class Guard {
  static const _channel = MethodChannel('com.dodo258.tracker/guard');

  static Future<GuardState> state() async =>
      GuardState.fromMap((await _channel.invokeMapMethod<dynamic, dynamic>('getState'))!);

  /// 弹系统的「激活设备管理员」，返回是否激活了。
  static Future<bool> enableAdmin() async {
    appLock.expectExternalPage();
    return await _channel.invokeMethod<bool>('enableAdmin') ?? false;
  }

  /// 调用方先验 App 密码。
  static Future<void> disableAdmin() => _channel.invokeMethod<void>('disableAdmin');
}
