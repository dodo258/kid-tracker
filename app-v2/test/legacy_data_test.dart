// 内存版存储来自 shared_preferences 自带的平台接口包，官方推荐的测试替身
// 用相对路径引 lib/：根目录的 flutter analyze 也会扫 app-v2，那时 app-v2 可能还没 pub get，
// package:traccar_client 会按根目录旧 App 的包解析（两个包同名），相对路径不受影响（见 README「检查」）
// ignore_for_file: depend_on_referenced_packages, avoid_relative_lib_imports
import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences_platform_interface/in_memory_shared_preferences_async.dart';
import 'package:shared_preferences_platform_interface/shared_preferences_async_platform_interface.dart';
import '../lib/l10n/app_localizations.dart';
import '../lib/password_hash.dart';
import '../lib/password_service.dart';
import '../lib/preferences.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

import 'support/fake_platform.dart' show enterPin;

/// 覆盖安装根目录旧 App（10.2.x）：两边都用同一份 SharedPreferences（同一个键），
/// 设备号、服务器、间隔原样读到；旧 App 存的 App 密码是 PBKDF2 哈希，v2 要认得。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  // 旧 App PasswordHash.create('2580') 存下的格式（盐固定，方便写死）
  const legacyHash = r'pbkdf2-sha256$10000$pin$AAECAwQFBgcICQoLDA0ODw==$UldoY7sN68iM/oTxdd6mVaI6eOijwCW1vc9iURLAJD4=';

  // P20 上旧 App 10.2.23 存的设置（键名和类型照旧 App 的 Preferences）
  final legacyPrefs = <String, Object>{
    Preferences.id: '20000001',
    Preferences.url: 'https://gps.example.com/osmand',
    Preferences.accuracy: 'high',
    Preferences.interval: 10,
    Preferences.distance: 50,
    Preferences.heartbeat: 300,
    Preferences.buffer: true,
    Preferences.stopDetection: true,
    Preferences.password: legacyHash,
    // 旧 App 才有的键，v2 不读也不删
    'min_interval': 5,
    'zone_radius': 200,
    'settings_version': 3,
  };

  Future<void> launchWith(Map<String, Object> stored) async {
    SharedPreferencesAsyncPlatform.instance = InMemorySharedPreferencesAsync.withData(stored);
    FlutterSecureStorage.setMockInitialValues({});
    Preferences.resetForTest();
    await Preferences.init();
  }

  group('覆盖安装旧 App', () {
    test('设备号、服务器、间隔原样交给 SDK，不重新生成', () async {
      await launchWith(Map.of(legacyPrefs));
      final config = Preferences.buildConfig();
      expect(config.deviceId, '20000001');
      expect(config.serverUrl, 'https://gps.example.com/osmand');
      expect(config.location.accuracy, Accuracy.high);
      expect(config.location.intervalSeconds, 10);
      expect(config.location.distanceMeters, 50);
      expect(config.location.heartbeatIntervalSeconds, 300);
      expect(config.buffer, isTrue);
    });

    test('旧 App 存的密码哈希原样保留，旧密码能验过、错的不行', () async {
      await launchWith(Map.of(legacyPrefs));
      await PasswordService.migrate();
      expect(Preferences.instance.getString(Preferences.password), legacyHash);
      expect(PasswordHash.matches(legacyHash, '2580'), isTrue);
      expect(PasswordHash.matches(legacyHash, '2581'), isFalse);
    });

    testWidgets('输密码框里输旧密码能进', (tester) async {
      await tester.runAsync(() => launchWith(Map.of(legacyPrefs)));
      bool? passed;
      await tester.pumpWidget(MaterialApp(
        localizationsDelegates: AppLocalizations.localizationsDelegates,
        supportedLocales: AppLocalizations.supportedLocales,
        locale: const Locale('zh'),
        home: Builder(
          builder: (context) => TextButton(
            onPressed: () async => passed = await PasswordService.authenticate(context),
            child: const Text('go'),
          ),
        ),
      ));
      await tester.tap(find.text('go'));
      await tester.pumpAndSettle();
      // 密码框是数字键（旧 App 的密码页，R6 搬来）
      await enterPin(tester, '2580');
      expect(passed, isTrue);
    });
  });

  group('密码', () {
    test('原版存在设置里的明文换成哈希，旧密码照样能用', () async {
      await launchWith({Preferences.id: '12345678', Preferences.password: '1357'});
      await PasswordService.migrate();
      final stored = Preferences.instance.getString(Preferences.password);
      expect(PasswordHash.isHashed(stored), isTrue);
      expect(PasswordHash.matches(stored, '1357'), isTrue);
    });

    test('原版更早存在 secure storage 的明文搬过来成哈希，并删掉原件', () async {
      await launchWith({Preferences.id: '12345678'});
      FlutterSecureStorage.setMockInitialValues({Preferences.password: 'old pw'});
      await PasswordService.migrate();
      expect(PasswordHash.matches(Preferences.instance.getString(Preferences.password), 'old pw'), isTrue);
      expect(await const FlutterSecureStorage().read(key: Preferences.password), isNull);
    });

    test('新设的密码存哈希，清空就删掉', () async {
      await launchWith({Preferences.id: '12345678'});
      await PasswordService.setPassword('8642');
      final stored = Preferences.instance.getString(Preferences.password);
      expect(stored, isNot(contains('8642')));
      expect(PasswordHash.matches(stored, '8642'), isTrue);
      await PasswordService.setPassword('');
      expect(Preferences.instance.getString(Preferences.password), isNull);
    });

    test('PBKDF2-HMAC-SHA256 对上 RFC 7914 的测试向量', () {
      String run(String p, String s, int c, int len) =>
          PasswordHash.pbkdf2(utf8.encode(p), utf8.encode(s), c, length: len).map((b) => b.toRadixString(16).padLeft(2, '0')).join();
      expect(run('password', 'salt', 4096, 32), 'c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a');
    });
  });

  group('新装', () {
    test('默认服务器是自建的，设备号是 8 位数字', () async {
      await launchWith({});
      final config = Preferences.buildConfig();
      expect(config.serverUrl, 'https://gps.example.com/osmand');
      expect(config.deviceId, matches(RegExp(r'^\d{8}$')));
    });

    test('前台通知按系统语言：中文「服务中」', () {
      expect(Preferences.localizationsFor([const Locale('zh', 'CN')]).notificationText, '服务中');
      expect(Preferences.localizationsFor([const Locale('en', 'US')]).notificationText, 'Running');
      // 别的语言退回中文
      expect(Preferences.localizationsFor([const Locale('fr')]).appTitle, '定位服务');
    });
  });
}
