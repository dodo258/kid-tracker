// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';
import '../../lib/main_screen.dart';
import '../../lib/setup/background_check.dart';
import '../../lib/setup/checklist.dart';
import '../../lib/setup/checklist_screen.dart';
import '../../lib/setup/guard.dart';

import '../support/fake_platform.dart';

/// 自检清单全部自动判定（R6）：系统项现读、厂商项看实际表现，家长不手动打勾。
void main() {
  BackgroundStatus status({Map<CheckItem, CheckState> items = const {}, String brand = 'huawei'}) => BackgroundStatus(
        items: {for (final item in CheckItem.values) item: items[item] ?? CheckState.ok},
        brand: brand,
        fingerprint: 'fp1',
        device: 'HUAWEI P20',
        rom: 'HarmonyOS 3.0.0',
        release: '10',
      );

  Map<HealthEvidence, HealthJudgement> health([Map<HealthEvidence, HealthVerdict> v = const {}]) =>
      {for (final e in HealthEvidence.values) e: HealthJudgement(v[e] ?? HealthVerdict.pending)};

  group('清单模型', () {
    test('厂商项一开始都是待验证：不算还差，也不挡开追踪', () {
      final c = buildChecklist(status(), health: health(), guard: const GuardState(admin: true, wantAdmin: true), passwordSet: true);
      expect(c.vendor.map((e) => e.item), [
        VendorItem.autostart,
        VendorItem.associatedLaunch,
        VendorItem.backgroundActivity,
        VendorItem.networkAccess,
        VendorItem.screenOffNetwork,
      ]);
      expect(c.vendor.every((e) => e.state == EntryState.pending), isTrue);
      expect(c.remaining, 0);
      expect(c.blocking, 0);
    });

    test('同一样表现判几项：重启没自己恢复，自启动、关联启动一起没生效，算进还差但不挡开追踪', () {
      final c = buildChecklist(
        status(),
        health: health({HealthEvidence.reboot: HealthVerdict.bad, HealthEvidence.send: HealthVerdict.ok}),
        guard: const GuardState(admin: true, wantAdmin: true),
        passwordSet: true,
      );
      final states = {for (final e in c.vendor) e.item: e.state};
      expect(states[VendorItem.autostart], EntryState.todo);
      expect(states[VendorItem.associatedLaunch], EntryState.todo);
      expect(states[VendorItem.backgroundActivity], EntryState.pending);
      expect(states[VendorItem.networkAccess], EntryState.done);
      expect(c.remaining, 2);
      expect(c.blocking, 0);
    });

    test('系统项没开、防卸载没开、没设密码：都算还差，都挡开追踪；建议项和不适用的不算', () {
      final c = buildChecklist(
        status(items: {
          CheckItem.backgroundLocation: CheckState.bad,
          CheckItem.exactAlarm: CheckState.na,
          CheckItem.wifiScan: CheckState.bad,
        }),
        health: health(),
        guard: const GuardState(admin: false, wantAdmin: false),
        passwordSet: false,
      );
      expect(c.blocking, 3);
      expect(c.remaining, 3);
      expect(c.notApplicable.map((e) => e.item), [CheckItem.exactAlarm]);
      expect(homePrompt(c, savedFingerprint: 'fp1'), HomePrompt.noPassword);
      expect(homePrompt(c, savedFingerprint: null), HomePrompt.firstRun);
      expect(homePrompt(c, savedFingerprint: 'old'), HomePrompt.systemUpdated);
    });

    test('三星从多任务里隐藏了本 App：厂商项不再有「锁定此应用程序」；别的品牌照旧要加锁', () {
      expect(vendorItemsFor('samsung'), [VendorItem.powerSaving]);
      for (final brand in ['honor', 'xiaomi', 'oppo', 'vivo', 'other']) {
        expect(vendorItemsFor(brand), contains(VendorItem.recentsLock), reason: brand);
      }
      final c = buildChecklist(status(brand: 'samsung'), health: health(), guard: const GuardState(admin: true, wantAdmin: true), passwordSet: true);
      expect(c.vendor.map((e) => e.item), [VendorItem.powerSaving]);
    });
  });

  group('清单页', () {
    late FakePlatform platform;

    setUp(() async {
      platform = FakePlatform()..brand = 'huawei';
      await platform.install();
      await platform.setPassword('1234');
    });

    Future<void> pumpChecklist(WidgetTester tester) async {
      tester.view.physicalSize = const Size(900, 3200);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(zhApp(const ChecklistScreen()));
      await tester.pumpAndSettle();
    }

    testWidgets('没有手动打勾：系统项现读，没开给「去开启」；厂商项写待验证和怎么验证', (tester) async {
      platform.items = {'backgroundLocation': 'bad'};
      await pumpChecklist(tester);
      expect(find.byType(Checkbox), findsNothing);
      expect(find.text('还差 1 项'), findsOneWidget);
      expect(find.text('去开启'), findsOneWidget);
      expect(find.textContaining('开着追踪重启一次手机'), findsNWidgets(2));
      expect(find.textContaining('开着追踪、锁屏放 15 分钟以上'), findsOneWidget);

      // 家长去系统设置开了，回到 App 自动打勾
      platform.items = {};
      for (final state in [
        AppLifecycleState.inactive,
        AppLifecycleState.hidden,
        AppLifecycleState.paused,
        AppLifecycleState.hidden,
        AppLifecycleState.inactive,
        AppLifecycleState.resumed,
      ]) {
        tester.binding.handleAppLifecycleStateChanged(state);
      }
      await tester.pumpAndSettle();
      expect(find.text('去开启'), findsNothing);
      expect(find.text('还有 5 项待验证'), findsOneWidget);
    });

    testWidgets('已生效、没生效：没生效给「去设置」，点了这项重新验证', (tester) async {
      platform.health = {'reboot': 'ok', 'breath': 'bad', 'send': 'ok'};
      await pumpChecklist(tester);
      expect(find.text('已生效：重启后自己接上了'), findsNWidgets(2));
      expect(find.textContaining('没生效：锁屏后报平安不按时'), findsOneWidget);
      expect(find.text('还差 1 项'), findsOneWidget);

      await tester.tap(find.text('去设置'));
      await tester.pumpAndSettle();
      expect(platform.healthResets, ['breath']);
      expect(find.text('去设置'), findsNothing);
      expect(find.text('全部完成'), findsNothing);
    });

    testWidgets('运行记录：没有异常写没有；有就写最近一次是什么、几点，附原文', (tester) async {
      await pumpChecklist(tester);
      expect(find.text('运行记录'), findsOneWidget);
      expect(find.text('没有记录到异常'), findsOneWidget);
      await tester.pumpWidget(const SizedBox());

      platform.lastFault = {
        'time': DateTime(2026, 11, 19, 3, 12).millisecondsSinceEpoch,
        'kind': 'rescue',
        'message': 'level 1, rebuilding location source: location fetch timed out 3 times in a row',
      };
      await pumpChecklist(tester);
      expect(find.text('上报卡住过，已自动重启定位或追踪'), findsOneWidget);
      expect(find.textContaining('2026-11-19 03:12'), findsOneWidget);
      expect(find.textContaining('location fetch timed out'), findsOneWidget);
    });

    testWidgets('联网这类没有设置页可跳：写菜单路径，改好了点「改好了」重新验证', (tester) async {
      platform.health = {'send': 'bad'};
      await pumpChecklist(tester);
      expect(find.text('改好了'), findsNWidgets(2));
      await tester.tap(find.text('改好了').first);
      await tester.pumpAndSettle();
      expect(platform.healthResets, ['send']);
    });

    testWidgets('防卸载没开：「去开启」先要 App 密码已设，然后弹系统激活', (tester) async {
      platform.admin = false;
      await pumpChecklist(tester);
      expect(find.text('还差 1 项'), findsOneWidget);
      await tester.tap(find.text('去开启'));
      await tester.pumpAndSettle();
      expect(platform.admin, isTrue);
      expect(find.text('去开启'), findsNothing);
    });
  });

  group('开追踪的门槛', () {
    late FakePlatform platform;

    setUp(() async {
      platform = FakePlatform()..brand = 'huawei';
      await platform.install();
      await platform.openedChecklist();
    });

    Future<void> pumpMain(WidgetTester tester) async {
      await tester.pumpWidget(zhApp(const MainScreen()));
      await tester.pumpAndSettle();
    }

    Future<void> unmount(WidgetTester tester) => tester.pumpWidget(const SizedBox());

    testWidgets('系统项没开：不让开追踪，提示还差几项并进清单', (tester) async {
      await platform.setPassword(null);
      platform.items = {'notifications': 'bad'};
      await pumpMain(tester);
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      expect(platform.sdkCalls, isNot(contains('start')));
      expect(find.text('稳定运行需要的设置'), findsWidgets);
      await unmount(tester);
    });

    testWidgets('厂商项没生效不挡：照样能开追踪（要开着追踪才验证得了）', (tester) async {
      await platform.setPassword('1234');
      platform.health = {'reboot': 'bad'};
      await pumpMain(tester);
      expect(find.textContaining('还差 2 项'), findsOneWidget);
      await tester.tap(find.byType(Switch));
      await tester.pumpAndSettle();
      await enterPin(tester, '1234');
      expect(platform.sdkCalls, contains('start'));
      await unmount(tester);
    });
  });
}
