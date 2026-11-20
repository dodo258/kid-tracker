// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import '../../lib/preferences.dart';
import '../../lib/setup/password_attempts.dart';

import '../support/fake_platform.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('输错计数', () {
    late int lockOuts;
    late PasswordAttempts attempts;

    setUp(() async {
      await FakePlatform().install();
      lockOuts = 0;
      attempts = PasswordAttempts(password: () => '1234', onLockedOut: () async => lockOuts++);
    });

    test('每输错一次少一次机会，输对清零', () async {
      expect(attempts.remaining, 5);
      expect(await attempts.check('0000'), PasswordCheck.wrong);
      expect(await attempts.check('1111'), PasswordCheck.wrong);
      expect(attempts.failures, 2);
      expect(attempts.remaining, 3);
      expect(await attempts.check('1234'), PasswordCheck.ok);
      expect(attempts.failures, 0);
      expect(attempts.remaining, 5);
      expect(lockOuts, 0);
    });

    test('连续输错 5 次：退出一次，计数清零重新开始', () async {
      for (var i = 0; i < 4; i++) {
        expect(await attempts.check('0000'), PasswordCheck.wrong);
      }
      expect(lockOuts, 0);
      expect(attempts.remaining, 1);
      expect(await attempts.check('0000'), PasswordCheck.lockedOut);
      expect(lockOuts, 1);
      expect(attempts.remaining, 5);
      expect(await attempts.check('0000'), PasswordCheck.wrong);
      expect(lockOuts, 1);
    });

    test('计数写进存储：App 重启（新建对象、重读存储）后还在', () async {
      await attempts.check('0000');
      await attempts.check('0000');
      await attempts.check('0000');
      // 丢掉内存缓存、从底层存储重读，等于 App 重启
      await Preferences.instance.reloadCache();
      expect(Preferences.instance.getInt(Preferences.passwordFailures), 3);
      final afterRestart = PasswordAttempts(password: () => '1234', onLockedOut: () async => lockOuts++);
      expect(afterRestart.remaining, 2);
      await afterRestart.check('0000');
      expect(await afterRestart.check('0000'), PasswordCheck.lockedOut);
      expect(lockOuts, 1);
      await Preferences.instance.reloadCache();
      expect(Preferences.instance.getInt(Preferences.passwordFailures), isNull);
    });
  });

  group('5 次后退出', () {
    late FakePlatform platform;
    late List<String> platformCalls;

    setUp(() async {
      platform = FakePlatform();
      await platform.install();
      platformCalls = [];
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        (call) async {
          platformCalls.add(call.method);
          return null;
        },
      );
    });

    tearDown(() {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        SystemChannels.platform,
        null,
      );
    });

    test('先交给 SDK 报 alarm=tampering（先存再发、联网补发），再退回桌面', () async {
      await lockOutApp();
      expect(platform.tampering, ['passwordFailures']);
      expect(platformCalls, ['SystemNavigator.pop']);
    });

    test('SDK 存不进去（还没配置）也照样退出', () async {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(
        const MethodChannel('traccar_client_sdk'),
        (call) async => throw PlatformException(code: 'IllegalStateException', message: 'Tracker not configured'),
      );
      await lockOutApp();
      expect(platformCalls, ['SystemNavigator.pop']);
    });
  });
}
