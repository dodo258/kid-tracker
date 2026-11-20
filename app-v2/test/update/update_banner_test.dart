// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'dart:async';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../../lib/main_screen.dart';
import '../../lib/update/apk_updater.dart';
import '../../lib/update/app_update.dart';
import '../../lib/update/update_banner.dart';

import '../support/fake_platform.dart';

/// 首页顶部的「有新版本」提示：有更高 versionCode 才出现，点了在 App 里下载、校验、弹安装确认。
void main() {
  late FakePlatform platform;

  setUp(() async {
    platform = FakePlatform();
    await platform.install();
    await platform.openedChecklist();
    await platform.setPassword('1234');
  });

  Future<void> pumpMain(WidgetTester tester) async {
    await tester.pumpWidget(zhApp(const MainScreen()));
    await tester.pumpAndSettle();
  }

  // MainScreen 有每分钟刷新的定时器，测试结束前拆掉
  Future<void> unmount(WidgetTester tester) => tester.pumpWidget(const SizedBox());

  final newer = '{"versionCode": 169, "versionName": "10.2.5", "apkUrl": "https://gps.example.com/app/a.apk", "sha256": "${'ab' * 32}", "notes": "修好重启后不上报"}';

  testWidgets('服务器上有更高 versionCode：首页顶部提示和更新说明', (tester) async {
    platform.latestJson = newer;
    await pumpMain(tester);

    expect(find.text('有新版本，点这里更新'), findsOneWidget);
    expect(find.text('修好重启后不上报'), findsOneWidget);
    expect(
      tester.getTopLeft(find.byType(UpdateBanner)).dy,
      lessThan(tester.getTopLeft(find.text('持续追踪')).dy),
    );
    await unmount(tester);
  });

  testWidgets('已经是最新版：不提示', (tester) async {
    platform.latestJson = newer;
    platform.installedVersionCode = 169;
    await pumpMain(tester);

    expect(find.byType(UpdateBanner), findsNothing);
    expect(platform.updateFetches, 1);
    await unmount(tester);
  });

  testWidgets('没网：静默，首页照常', (tester) async {
    await pumpMain(tester);

    expect(platform.updateFetches, 1);
    expect(find.byType(UpdateBanner), findsNothing);
    expect(find.byType(SnackBar), findsNothing);
    expect(find.text('持续追踪'), findsOneWidget);
    await unmount(tester);
  });

  final info = parseUpdateInfo(newer, Uri.parse('https://gps.example.com/app/latest.json'))!;

  Future<FakeUpdater> pumpBanner(WidgetTester tester, {bool allowed = true}) async {
    final updater = FakeUpdater()..allowed = allowed;
    await tester.pumpWidget(zhApp(Scaffold(body: UpdateBanner(info: info, updater: updater))));
    return updater;
  }

  Future<void> tapBanner(WidgetTester tester, String title) async {
    await tester.tap(find.text(title));
    await tester.pump();
  }

  testWidgets('点提示：App 里下载，显示进度，下好弹安装确认', (tester) async {
    final updater = await pumpBanner(tester);
    updater.gate = Completer();
    await tapBanner(tester, '有新版本，点这里更新');

    expect(find.text('正在下载新版本'), findsOneWidget);
    updater.progress!(0.42);
    await tester.pump();
    expect(find.text('42%'), findsOneWidget);
    expect(find.byType(LinearProgressIndicator), findsOneWidget);

    // 下载中再点不重复下
    await tapBanner(tester, '正在下载新版本');
    expect(updater.downloads, 1);

    updater.gate!.complete();
    await tester.pumpAndSettle();
    expect(updater.downloads, 1);
    expect(updater.installs, 1);
    expect(find.text('下载好了，点这里安装'), findsOneWidget);
    expect(find.byType(SnackBar), findsNothing);

    // 安装确认点了取消：再点提示直接装，不重下
    await tapBanner(tester, '下载好了，点这里安装');
    await tester.pumpAndSettle();
    expect(updater.downloads, 1);
    expect(updater.installs, 2);
  });

  testWidgets('sha256 不符：不装，提示一句，回到起点', (tester) async {
    final updater = await pumpBanner(tester);
    updater.failure = DownloadFailure.checksum;
    await tapBanner(tester, '有新版本，点这里更新');
    await tester.pumpAndSettle();

    expect(updater.installs, 0);
    expect(find.text('安装包校验不对，没有安装'), findsOneWidget);
    expect(find.text('有新版本，点这里更新'), findsOneWidget);
  });

  testWidgets('下载失败：提示检查网络，再点重下', (tester) async {
    final updater = await pumpBanner(tester);
    updater.failure = DownloadFailure.network;
    await tapBanner(tester, '有新版本，点这里更新');
    await tester.pumpAndSettle();

    expect(updater.installs, 0);
    expect(find.text('下载失败，检查网络后再点一次'), findsOneWidget);

    updater.failure = null;
    await tapBanner(tester, '有新版本，点这里更新');
    await tester.pumpAndSettle();
    expect(updater.downloads, 2);
    expect(updater.installs, 1);
  });

  testWidgets('没开「安装未知应用」：先跳本 App 的开关页，开了回来自动装', (tester) async {
    final updater = await pumpBanner(tester, allowed: false);
    await tapBanner(tester, '有新版本，点这里更新');
    await tester.pumpAndSettle();

    expect(updater.settingsOpened, 1);
    expect(updater.installs, 0);
    expect(find.text('请允许本应用安装更新，返回后自动安装'), findsOneWidget);

    updater.allowed = true;
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(updater.installs, 1);

    // 之后再回到前台不会又弹
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(updater.installs, 1);
  });

  testWidgets('没开权限回来还是没开：不装，下次点再跳', (tester) async {
    final updater = await pumpBanner(tester, allowed: false);
    await tapBanner(tester, '有新版本，点这里更新');
    await tester.pumpAndSettle();
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.paused);
    tester.binding.handleAppLifecycleStateChanged(AppLifecycleState.resumed);
    await tester.pumpAndSettle();
    expect(updater.installs, 0);

    await tapBanner(tester, '下载好了，点这里安装');
    await tester.pumpAndSettle();
    expect(updater.settingsOpened, 2);
  });

  testWidgets('安装包身份不对（别的包名、别的签名、版本不更新）：不交给系统、删掉、提示，回到起点（审计 M1）', (tester) async {
    final updater = await pumpBanner(tester);
    updater.identityProblem = 'signature';
    await tapBanner(tester, '有新版本，点这里更新');
    await tester.pumpAndSettle();

    expect(updater.installs, 0);
    expect(updater.clears, 1);
    expect(find.text('下载的安装包不是本 App 的正式版本，没有安装'), findsOneWidget);
    expect(find.text('有新版本，点这里更新'), findsOneWidget);
  });

  testWidgets('安装界面打不开（包被系统清掉等）：提示，回到起点重下', (tester) async {
    final updater = await pumpBanner(tester);
    updater.installResult = false;
    await tapBanner(tester, '有新版本，点这里更新');
    await tester.pumpAndSettle();

    expect(find.text('没打开安装界面'), findsOneWidget);
    expect(find.text('有新版本，点这里更新'), findsOneWidget);
  });
}

/// 假的下载安装器：不联网、不碰文件，记下每一步。
class FakeUpdater extends ApkUpdater {
  bool allowed = true;
  bool installResult = true;
  DownloadFailure? failure;
  Completer<void>? gate;
  void Function(double? progress)? progress;
  int downloads = 0;
  int installs = 0;
  int settingsOpened = 0;
  int clears = 0;
  String? identityProblem;

  @override
  Future<File> download(UpdateInfo info, {void Function(double? progress)? onProgress}) async {
    downloads++;
    progress = onProgress;
    onProgress?.call(0);
    await gate?.future;
    if (failure != null) throw ApkDownloadException(failure!, 'fake');
    return File('/cache/update/${info.versionCode}.apk');
  }

  @override
  Future<bool> canInstall() async => allowed;

  @override
  Future<String?> verify(File apk) async => identityProblem;

  @override
  Future<void> clear() async => clears++;

  @override
  Future<bool> openInstallSettings() async {
    settingsOpened++;
    return true;
  }

  @override
  Future<bool> install(File apk) async {
    installs++;
    return installResult;
  }
}
