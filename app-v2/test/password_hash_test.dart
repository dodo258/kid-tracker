// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'dart:convert';
import 'dart:io';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/app_lock.dart';
import '../lib/password_hash.dart';
import '../lib/password_service.dart';
import '../lib/preferences.dart';
import '../lib/setup/password_dialog.dart';

import 'support/fake_platform.dart';

/// App 密码只存加盐哈希；老版本的明文（设置里、更早的 secure storage）启动时换成哈希，旧密码照样能解。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('PasswordHash', () {
    test('PBKDF2-HMAC-SHA256 对上 RFC 7914 的测试向量', () {
      String run(String p, String s, int c, int len) =>
          PasswordHash.pbkdf2(utf8.encode(p), utf8.encode(s), c, length: len).map((b) => b.toRadixString(16).padLeft(2, '0')).join();
      expect(run('passwd', 'salt', 1, 64),
          '55ac046e56e3089fec1691c22544b605f94185216dde0465e68b9d57c20dacbc49ca9cccf179b645991664b39d77ef317c71b845b1e30bd509112041d3a19783');
      expect(run('password', 'salt', 4096, 32), 'c5e478d59288c841aa530db6845c4c8d962893a001ce4e11a4963873aa98134a');
    });

    test('存的不是明文，同一个密码每次加的盐不同；对的认、错的不认', () {
      final a = PasswordHash.create('2580');
      final b = PasswordHash.create('2580');
      expect(a, isNot(contains('2580')));
      expect(a, isNot(b));
      expect(PasswordHash.matches(a, '2580'), isTrue);
      expect(PasswordHash.matches(b, '2580'), isTrue);
      expect(PasswordHash.matches(a, '2581'), isFalse);
      expect(PasswordHash.matches(a, ''), isFalse);
    });

    test('记得是不是纯数字（选数字键盘还是系统键盘）', () {
      expect(PasswordHash.isPin(PasswordHash.create('2580')), isTrue);
      expect(PasswordHash.isPin(PasswordHash.create('abc12')), isFalse);
      expect(isPin(PasswordHash.create('0123')), isTrue);
    });

    test('老版本的明文照样认；没设、格式坏了不认', () {
      expect(PasswordHash.matches('pass1', 'pass1'), isTrue);
      expect(PasswordHash.matches('pass1', 'pass2'), isFalse);
      expect(PasswordHash.isPin('1234'), isTrue);
      expect(PasswordHash.isPin('ab12'), isFalse);
      expect(PasswordHash.matches(null, ''), isFalse);
      expect(PasswordHash.matches('', ''), isFalse);
      expect(PasswordHash.matches(r'pbkdf2-sha256$x$pin$AA==$AA==', '1'), isFalse);
      expect(PasswordHash.matches(r'pbkdf2-sha256$10$pin$%%%$AA==', '1'), isFalse);
    });
  });

  group('启动时迁移', () {
    late FakePlatform platform;

    setUp(() async {
      platform = FakePlatform();
      await platform.install();
    });

    test('设置里的明文换成哈希，旧密码能解锁', () async {
      FlutterSecureStorage.setMockInitialValues({});
      await Preferences.instance.setString(Preferences.password, '1357');
      await PasswordService.migrate();
      final stored = Preferences.instance.getString(Preferences.password);
      expect(stored, storesPassword('1357'));
      final lock = AppLock(password: () => Preferences.instance.getString(Preferences.password))..start();
      expect(lock.locked, isTrue);
      expect(lock.unlock('1357'), isTrue);
      // 再跑一次不重复处理
      await PasswordService.migrate();
      expect(Preferences.instance.getString(Preferences.password), stored);
    });

    test('更早版本存在 secure storage 的明文（带字母）搬到设置里成哈希，并删掉原件', () async {
      FlutterSecureStorage.setMockInitialValues({Preferences.password: 'old pw'});
      await PasswordService.migrate();
      expect(Preferences.instance.getString(Preferences.password), storesPassword('old pw'));
      expect(isPin(Preferences.instance.getString(Preferences.password)), isFalse);
      expect(await const FlutterSecureStorage().read(key: Preferences.password), isNull);
    });

    test('没设密码什么都不写', () async {
      FlutterSecureStorage.setMockInitialValues({});
      await PasswordService.migrate();
      expect(Preferences.instance.getString(Preferences.password), isNull);
    });

    test('设密码存的是哈希', () async {
      await PasswordService.setPassword('8642');
      expect(Preferences.instance.getString(Preferences.password), storesPassword('8642'));
    });
  });

  group('系统备份不带走', () {
    // 换机恢复了设备号，两部手机会报到同一个设备下；App 密码、离线定位、录制也不进备份
    String read(String path) => File(path).readAsStringSync();
    const res = 'android/app/src/main/res/xml';

    test('清单声明了两份备份规则', () {
      final manifest = read('android/app/src/main/AndroidManifest.xml');
      expect(manifest, contains('android:dataExtractionRules="@xml/data_extraction_rules"'));
      expect(manifest, contains('android:fullBackupContent="@xml/backup_rules"'));
    });

    for (final (file, sections) in [
      ('$res/data_extraction_rules.xml', ['cloud-backup', 'device-transfer']),
      ('$res/backup_rules.xml', ['full-backup-content']),
    ]) {
      test('$file 排除设置（含设备号、密码）、定位库、录制', () {
        final xml = read(file);
        for (final section in sections) {
          final body = RegExp('<$section>(.*?)</$section>', dotAll: true).firstMatch(xml)?.group(1) ?? '';
          for (final rule in [
            '<exclude domain="sharedpref" path="." />',
            '<exclude domain="database" path="tracker.db" />',
            '<exclude domain="database" path="tracker.db-wal" />',
            '<exclude domain="external" path="recorder" />',
          ]) {
            expect(body, contains(rule), reason: '$section 缺 $rule');
          }
        }
      });
    }
  });
}
