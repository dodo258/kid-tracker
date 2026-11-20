// 内存版存储来自 shared_preferences 自带的平台接口包（随 shared_preferences 一起装好），官方推荐的测试替身
// ignore_for_file: depend_on_referenced_packages, avoid_relative_lib_imports
import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';

import '../../lib/l10n/app_localizations.dart';
import '../../lib/password_hash.dart';
import '../../lib/preferences.dart';
import '../../lib/setup/background_check.dart';
import '../../lib/setup/passcode_field.dart';
import '../../lib/update/app_update.dart';

/// 假的原生侧（从根目录旧 App 的测试搬来、去掉三星版和无障碍）：跟踪 SDK、自检清单、防卸载三个 MethodChannel，
/// 加内存存储和检查更新的假网络。
class FakePlatform {
  bool tracking = false;

  /// 清单系统项的状态：ok / bad / na，没列的都是 ok。
  Map<String, String> items = {};

  /// 清单的品牌（原生侧归好的 xiaomi、huawei、honor、oppo、vivo、samsung、other）。
  String brand = 'other';

  /// 厂商项的实际表现（SDK getHealth）：reboot / breath / send → pending / ok / bad，没列的是 pending。
  Map<String, String> health = {};

  /// 最近一次异常（SDK getLastFault）：time 毫秒时间戳、kind、message；null 是没有。
  Map<String, Object>? lastFault;

  /// 被要求重新验证的证据（resetHealth）。
  final healthResets = <String>[];

  /// 防卸载开没开、家长开过没有。
  bool admin = true;
  bool wantAdmin = false;

  /// SDK 诊断日志（getLogs 的返回）：time 毫秒时间戳、message。
  List<Map<String, Object>> logs = [];

  /// SDK 收到的调用（start、stop 等），用来断言追踪有没有被开、被关。
  final sdkCalls = <String>[];

  /// reportTampering 收到的原因。
  final tampering = <String>[];

  /// 假服务器上 latest.json 的内容；null 表示没网。
  String? latestJson;

  /// 本机装的 versionCode。
  int installedVersionCode = 168;

  /// 检查更新联网的次数。
  int updateFetches = 0;

  static const fingerprint = 'fp1';
  static final _memory = InMemorySharedPreferencesAsync.empty();
  static const _sdk = MethodChannel('traccar_client_sdk');
  static const _check = MethodChannel('com.dodo258.tracker/background_check');
  static const _guard = MethodChannel('com.dodo258.tracker/guard');
  static const _recorder = MethodChannel('com.dodo258.tracker/recorder');

  /// 测试用录制：开没开、出错原因。
  bool recording = false;
  String? recorderError;

  /// 每个测试开头调用：清空存储，装上假的 MethodChannel。
  Future<void> install() async {
    // BackgroundCheck 的存储对象是静态的，绑定的是第一次装上的平台实例：一直用同一个内存存储，每个测试清空
    SharedPreferencesAsyncPlatform.instance = _memory;
    await SharedPreferencesAsync().clear();
    await Preferences.init();
    await Preferences.instance.clear();
    appUpdate = AppUpdate(
      fetch: (_) async {
        updateFetches++;
        return latestJson ?? (throw const SocketException('offline'));
      },
      currentVersionCode: () async => installedVersionCode,
    );
    final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
    messenger.setMockMethodCallHandler(_sdk, (call) async {
      sdkCalls.add(call.method);
      switch (call.method) {
        case 'isTracking':
          return tracking;
        case 'getLogs':
          return logs;
        case 'start':
          tracking = true;
        case 'stop':
          tracking = false;
        case 'reportTampering':
          tampering.add((call.arguments as Map)['reason'] as String);
          return 1;
        case 'getHealth':
          return {
            for (final e in ['reboot', 'breath', 'send']) e: {'verdict': health[e] ?? 'pending', 'detail': null},
          };
        case 'getLastFault':
          return lastFault;
        case 'resetHealth':
          final evidence = (call.arguments as Map)['evidence'] as String;
          healthResets.add(evidence);
          health.remove(evidence);
      }
      return null;
    });
    messenger.setMockMethodCallHandler(_check, (call) async {
      if (call.method != 'getStatus') return true;
      return {
        'items': {for (final item in CheckItem.values) item.name: items[item.name] ?? 'ok'},
        'brand': brand,
        'fingerprint': fingerprint,
        'device': 'Pixel',
        'rom': null,
        'release': '15',
      };
    });
    messenger.setMockMethodCallHandler(_recorder, (call) async {
      if (call.method == 'setOn') {
        recording = call.arguments as bool;
        recorderError = null;
      }
      if (call.method == 'files') return <String>[];
      return {'on': recording, 'running': recording, 'rows': 0, 'files': 2, 'bytes': 3145728, 'error': recorderError};
    });
    messenger.setMockMethodCallHandler(_guard, (call) async {
      switch (call.method) {
        case 'getState':
          return {'admin': admin, 'wantAdmin': wantAdmin};
        case 'disableAdmin':
          admin = false;
          wantAdmin = false;
        case 'enableAdmin':
          admin = true;
          wantAdmin = true;
          return true;
      }
      return null;
    });
  }

  /// 打开过清单（首页不再显示「第一次使用」）。
  Future<void> openedChecklist() => BackgroundCheck.saveFingerprint(fingerprint);

  Future<void> setPassword(String? password) async {
    if (password == null) {
      await Preferences.instance.remove(Preferences.password);
    } else {
      await Preferences.instance.setString(Preferences.password, PasswordHash.create(password));
    }
  }
}

/// 存着的 App 密码（哈希）对应 [password]，且不是明文。
Matcher storesPassword(String password) => predicate<String?>(
      (stored) => stored != password && PasswordHash.matches(stored, password),
      'stores a hash of "$password"',
    );

/// 中文界面的测试外壳。
Widget zhApp(Widget home, {TransitionBuilder? builder}) => MaterialApp(
      locale: const Locale('zh'),
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      builder: builder,
      home: home,
    );

/// 在密码页的数字键盘上按出 [pin]，再按确认键。
Future<void> enterPin(WidgetTester tester, String pin) async {
  final field = find.byType(PasscodeField);
  for (final digit in pin.split('')) {
    await tester.tap(find.descendant(of: field, matching: find.text(digit)));
    await tester.pump();
  }
  await tester.tap(find.descendant(of: field, matching: find.byIcon(Icons.check)));
  await tester.pumpAndSettle();
}
