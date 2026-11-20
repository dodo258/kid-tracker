// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter_test/flutter_test.dart';
import '../../lib/preferences.dart';
import '../../lib/update/app_update.dart';

import '../support/fake_platform.dart';

/// 检查更新：latest.json 解析、版本比较、一天一次、失败静默。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  final source = Uri.parse('https://gps.example.com/app/latest.json');

  final hash = 'ab' * 32;
  String latest({Object? code = 169, String apk = 'https://gps.example.com/app/dingwei-169.apk', Object? sha = 'default'}) =>
      jsonEncode({
        'versionCode': code,
        'versionName': '10.2.5',
        'apkUrl': apk,
        if (sha != null) 'sha256': sha == 'default' ? hash : sha,
        'notes': '修好重启后不上报',
      });

  group('parseUpdateInfo', () {
    test('完整的 latest.json', () {
      final info = parseUpdateInfo(latest(), source)!;
      expect(info.versionCode, 169);
      expect(info.versionName, '10.2.5');
      expect(info.apkUrl.toString(), 'https://gps.example.com/app/dingwei-169.apk');
      expect(info.sha256, hash);
      expect(info.notes, '修好重启后不上报');
    });

    test('sha256 大写也认，统一成小写', () {
      expect(parseUpdateInfo(latest(sha: ' ${'AB' * 32} '), source)!.sha256, hash);
    });

    test('versionCode 写成数字字符串也认；apk 写相对路径按 latest.json 所在目录补全', () {
      final info = parseUpdateInfo('{"versionCode": " 170 ", "apk": "dingwei-170.apk", "sha256": "$hash"}', source)!;
      expect(info.versionCode, 170);
      expect(info.apkUrl.toString(), 'https://gps.example.com/app/dingwei-170.apk');
      expect(info.versionName, '');
      expect(info.notes, '');
    });

    test('格式不对返回 null，不抛错', () {
      for (final body in [
        '',
        '<html>404</html>',
        '[1, 2]',
        '{"versionName": "10.2.5", "apkUrl": "https://a/x.apk"}',
        latest(code: 0),
        latest(code: -3),
        latest(code: 169.5),
        latest(code: 'abc'),
        '{"versionCode": 169}',
        latest(apk: ''),
        latest(apk: 'javascript:alert(1)'),
        latest(apk: 'intent://x#Intent;end'),
        latest(apk: 'file:///sdcard/x.apk'),
        latest(sha: null),
        latest(sha: ''),
        latest(sha: 'ab' * 31),
        latest(sha: 'zz' * 32),
        latest(sha: 123),
      ]) {
        expect(parseUpdateInfo(body, source), isNull, reason: body);
      }
    });
  });

  test('isNewer 只比 versionCode', () {
    final info = parseUpdateInfo(latest(), source)!;
    expect(isNewer(info, 168), isTrue);
    expect(isNewer(info, 169), isFalse);
    expect(isNewer(info, 170), isFalse);
  });

  test('checkDue：没查过、满 24 小时、时间被往回调都要查；不满 24 小时不查', () {
    final last = DateTime(2026, 11, 16, 9);
    expect(checkDue(null, last), isTrue);
    expect(checkDue(last, last.add(const Duration(hours: 23, minutes: 59))), isFalse);
    expect(checkDue(last, last.add(const Duration(days: 1))), isTrue);
    expect(checkDue(last, last.subtract(const Duration(minutes: 1))), isTrue);
  });

  group('AppUpdate.available', () {
    late FakePlatform platform;
    late DateTime now;
    late int fetches;
    late FutureOr<String> Function() serve;
    late int installed;

    AppUpdate checker() => AppUpdate(
      latestUrl: source,
      fetch: (url) async {
        expect(url, source);
        fetches++;
        return serve();
      },
      currentVersionCode: () async => installed,
      now: () => now,
    );

    setUp(() async {
      platform = FakePlatform();
      await platform.install();
      now = DateTime(2026, 11, 16, 9);
      fetches = 0;
      installed = 168;
      serve = latest;
    });

    test('服务器上更新：返回新版本', () async {
      final info = await checker().available();
      expect(info?.versionCode, 169);
      expect(fetches, 1);
    });

    test('服务器上和本机一样或更旧：不提示', () async {
      installed = 169;
      expect(await checker().available(), isNull);
      installed = 200;
      expect(await checker().available(), isNull);
    });

    test('一天只联网一次：当天再打开用存着的结果，App 重启后也是', () async {
      expect((await checker().available())?.versionCode, 169);
      now = now.add(const Duration(hours: 5));
      expect((await checker().available())?.versionCode, 169);
      expect(fetches, 1);

      now = now.add(const Duration(hours: 19));
      serve = () => latest(code: 171);
      expect((await checker().available())?.versionCode, 171);
      expect(fetches, 2);
    });

    test('装了新版本后提示消失', () async {
      expect(await checker().available(), isNotNull);
      installed = 169;
      expect(await checker().available(), isNull);
      expect(fetches, 1);
    });

    test('失败静默：没网、超时、服务器 404、内容不对都返回 null，不记检查时间，下次打开再查', () async {
      final failures = <FutureOr<String> Function()>[
        () => throw const SocketException('offline'),
        () => throw TimeoutException('slow'),
        () => throw const HttpException('HTTP 404'),
        () => '<html>404</html>',
      ];
      for (final fail in failures) {
        serve = fail;
        expect(await checker().available(), isNull);
      }
      expect(fetches, failures.length);
      expect(Preferences.instance.getInt(Preferences.updateCheckedAt), isNull);

      serve = latest;
      expect((await checker().available())?.versionCode, 169);
    });

    test('当天已查到新版本、之后联网失败：继续显示存着的新版本', () async {
      expect(await checker().available(), isNotNull);
      now = now.add(const Duration(days: 1));
      serve = () => throw const SocketException('offline');
      expect((await checker().available())?.versionCode, 169);
      expect(fetches, 2);
    });

    test('拿不到本机版本号：静默返回 null', () async {
      final broken = AppUpdate(
        latestUrl: source,
        fetch: (_) async => latest(),
        currentVersionCode: () async => throw StateError('no plugin'),
        now: () => now,
      );
      expect(await broken.available(), isNull);
    });

    test('同时几次调用只联网一次', () async {
      final gate = Completer<String>();
      serve = () => gate.future;
      final c = checker();
      final first = c.available();
      final second = c.available();
      gate.complete(latest());
      expect((await first)?.versionCode, 169);
      expect((await second)?.versionCode, 169);
      expect(fetches, 1);
    });
  });
}
