// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import '../../lib/update/apk_updater.dart';
import '../../lib/update/app_update.dart';

/// App 内下载安装：下到缓存目录、核对 sha256、失败原因、交给原生安装。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();
  const channel = MethodChannel('com.dodo258.tracker/update');
  final apkBytes = utf8.encode('PK fake apk ' * 1000);
  final goodHash = sha256.convert(apkBytes).toString();
  final url = Uri.parse('https://gps.example.com/app/dingwei-172.apk');

  late Directory cache;
  late List<MethodCall> calls;
  late bool allowed;
  late int opens;

  UpdateInfo info({String? hash}) =>
      UpdateInfo(versionCode: 172, versionName: '10.2.8', apkUrl: url, sha256: hash ?? goodHash, notes: '');

  /// 按 [chunks] 分块发；[length] 是响应头里的长度。
  OpenDownload serve(List<List<int>> chunks, {int? length, Object? error}) => (u) async {
        expect(u, url);
        opens++;
        Stream<List<int>> body() async* {
          for (final chunk in chunks) {
            yield chunk;
          }
          if (error != null) throw error;
        }

        return (length: length, body: body());
      };

  ApkUpdater updater(OpenDownload open) => ApkUpdater(open: open, channel: channel);

  List<FileSystemEntity> leftovers() =>
      Directory('${cache.path}/update').existsSync() ? Directory('${cache.path}/update').listSync() : [];

  setUp(() {
    cache = Directory.systemTemp.createTempSync('apk_updater_test');
    calls = [];
    allowed = true;
    opens = 0;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      switch (call.method) {
        case 'cacheDir':
          return cache.path;
        case 'canInstall':
          return allowed;
        case 'install':
          return File((call.arguments as Map)['path'] as String).existsSync();
        case 'openInstallSettings':
          return true;
      }
      return null;
    });
  });

  tearDown(() => cache.deleteSync(recursive: true));

  test('下载成功：存进缓存的 update 目录，进度从 0 到 1', () async {
    final progress = <double?>[];
    final half = apkBytes.length ~/ 2;
    final apk = await updater(serve([apkBytes.sublist(0, half), apkBytes.sublist(half)], length: apkBytes.length))
        .download(info(), onProgress: progress.add);

    expect(apk.path, '${cache.path}/update/172.apk');
    expect(apk.readAsBytesSync(), apkBytes);
    expect(progress.first, 0);
    expect(progress.last, 1);
    expect(progress, orderedEquals([...progress]..sort()));
    expect(leftovers().map((f) => f.path), [apk.path]);
  });

  test('服务器没给长度：进度报 null，照样下完', () async {
    final progress = <double?>[];
    await updater(serve([apkBytes])).download(info(), onProgress: progress.add);
    expect(progress, everyElement(isNull));
  });

  test('sha256 不符：拒装，删掉下的文件', () async {
    final u = updater(serve([apkBytes], length: apkBytes.length));
    await expectLater(
      u.download(info(hash: 'ab' * 32)),
      throwsA(isA<ApkDownloadException>().having((e) => e.failure, 'failure', DownloadFailure.checksum)),
    );
    expect(leftovers(), isEmpty);
  });

  test('下载失败都算网络问题：连不上、中途断、没下完、太大，不留半截文件', () async {
    final failures = <OpenDownload>[
      (_) async => throw const SocketException('offline'),
      (_) async => throw const HttpException('HTTP 404'),
      serve([apkBytes.sublist(0, 10)], length: apkBytes.length, error: TimeoutException('stalled')),
      serve([apkBytes.sublist(0, 10)], length: apkBytes.length),
      serve([], length: ApkUpdater.maxBytes + 1),
    ];
    for (final open in failures) {
      await expectLater(
        updater(open).download(info()),
        throwsA(isA<ApkDownloadException>().having((e) => e.failure, 'failure', DownloadFailure.network)),
      );
      expect(leftovers(), isEmpty);
    }
  });

  test('已经下好且校验对的不重下；换了新版本先清掉旧包', () async {
    final u = updater(serve([apkBytes], length: apkBytes.length));
    await u.download(info());
    await u.download(info());
    expect(opens, 1);

    File('${cache.path}/update/171.apk').writeAsStringSync('old');
    final other = UpdateInfo(versionCode: 173, versionName: '', apkUrl: url, sha256: goodHash, notes: '');
    await u.download(other);
    expect(opens, 2);
    expect(leftovers().map((f) => f.path), ['${cache.path}/update/173.apk']);
  });

  test('缓存里的包被改过：校验不对就重下', () async {
    final u = updater(serve([apkBytes], length: apkBytes.length));
    final apk = await u.download(info());
    apk.writeAsStringSync('tampered');
    await u.download(info());
    expect(opens, 2);
    expect(apk.readAsBytesSync(), apkBytes);
  });

  test('安装：权限、跳设置、弹安装确认都走原生；原生出错当作 false', () async {
    final u = updater(serve([apkBytes], length: apkBytes.length));
    final apk = await u.download(info());
    expect(await u.canInstall(), isTrue);
    allowed = false;
    expect(await u.canInstall(), isFalse);
    expect(await u.openInstallSettings(), isTrue);
    expect(await u.install(apk), isTrue);
    expect(calls.last.arguments, {'path': apk.path});

    await u.clear();
    expect(Directory('${cache.path}/update').existsSync(), isFalse);
    expect(await u.install(apk), isFalse);

    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (_) async => throw PlatformException(code: 'x'));
    expect(await u.install(apk), isFalse);
    await u.clear(); // 拿不到缓存目录也不抛
  });
}
