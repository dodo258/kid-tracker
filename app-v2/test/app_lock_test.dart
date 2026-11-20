// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/app_lock.dart';
import '../lib/password_service.dart';
import '../lib/preferences.dart';
import '../lib/setup/password_attempts.dart';

import 'support/fake_platform.dart';

void main() {
  group('什么时候锁', () {
    late String? password;
    late DateTime now;
    late AppLock lock;

    setUp(() {
      password = '1234';
      now = DateTime(2026, 11, 16, 12);
      lock = AppLock(password: () => password, now: () => now);
    });

    test('没设密码：冷启动、回到前台都不锁', () {
      password = null;
      lock.start();
      expect(lock.locked, isFalse);
      lock.onHidden();
      lock.onShown();
      expect(lock.locked, isFalse);
      password = '';
      lock.onHidden();
      expect(lock.locked, isFalse);
    });

    test('设了密码：冷启动就锁，输错还锁着，输对解锁', () {
      lock.start();
      expect(lock.locked, isTrue);
      expect(lock.unlock('0000'), isFalse);
      expect(lock.unlock(''), isFalse);
      expect(lock.locked, isTrue);
      expect(lock.unlock('1234'), isTrue);
      expect(lock.locked, isFalse);
    });

    test('退到后台就锁，回来要再输', () {
      lock.onHidden();
      expect(lock.locked, isTrue);
      lock.onShown();
      expect(lock.locked, isTrue);
    });

    test('App 自己打开系统设置页：10 分钟内回来不锁，只豁免这一次', () {
      lock.expectExternalPage();
      now = now.add(const Duration(seconds: 1));
      lock.onHidden();
      expect(lock.locked, isFalse);
      now = now.add(const Duration(minutes: 9));
      lock.onShown();
      expect(lock.locked, isFalse);
      // 下一次自己按 Home 离开照常锁
      lock.onHidden();
      expect(lock.locked, isTrue);
    });

    test('打开系统设置页后超过 10 分钟才回来：锁', () {
      lock.expectExternalPage();
      lock.onHidden();
      now = now.add(const Duration(minutes: 11));
      lock.onShown();
      expect(lock.locked, isTrue);
    });

    test('设置页没打开（很久以后才退到后台）：照常锁', () {
      lock.expectExternalPage();
      now = now.add(const Duration(minutes: 1));
      lock.onHidden();
      expect(lock.locked, isTrue);
    });

    test('解锁后，等着解锁的调用接着往下走', () async {
      lock.start();
      var done = false;
      final waiting = lock.whenUnlocked().then((_) => done = true);
      await Future<void>.delayed(Duration.zero);
      expect(done, isFalse);
      lock.unlock('1234');
      await waiting;
      expect(done, isTrue);
      await lock.whenUnlocked();
    });

    test('锁着时等解锁的操作，家长没解锁就离开：作废，不留到下次解锁（审计 M2）', () async {
      lock.start();
      final waiting = lock.whenUnlocked();
      lock.onHidden();
      expect(await waiting, isFalse);
      // 下次为看 App 解锁，不会顺带批准刚才那个
      final next = lock.whenUnlocked();
      lock.unlock('1234');
      expect(await next, isTrue);
    });
  });

  group('锁着时来的操作（链接、桌面快捷）', () {
    late FakePlatform platform;
    final home = GlobalKey();

    setUp(() async {
      platform = FakePlatform();
      await platform.install();
      await platform.setPassword('1234');
      appLock.start();
    });

    Future<Future<bool>> pumpLockedAndAsk(WidgetTester tester) async {
      await tester.pumpWidget(
        zhApp(
          Scaffold(body: Text('首页内容', key: home)),
          builder: (context, child) => AppLockGate(lock: appLock, child: child!),
        ),
      );
      await tester.pumpAndSettle();
      expect(find.text('输入 App 密码'), findsOneWidget);
      return PasswordService.authenticate(home.currentContext!, action: '停止追踪（家长会收到提醒）');
    }

    testWidgets('解锁只说明密码对了：还要家长确认这一个操作，取消就不做（审计 M2）', (tester) async {
      final result = await pumpLockedAndAsk(tester);
      var done = false;
      result.then((_) => done = true);
      await enterPin(tester, '1234');
      expect(find.text('首页内容'), findsOneWidget);
      expect(find.text('要继续吗？'), findsOneWidget);
      expect(find.text('停止追踪（家长会收到提醒）'), findsOneWidget);
      expect(done, isFalse, reason: '解锁不等于同意');
      await tester.tap(find.text('取消'));
      await tester.pumpAndSettle();
      expect(await result, isFalse);
    });

    testWidgets('解锁后确认了才做', (tester) async {
      final result = await pumpLockedAndAsk(tester);
      await enterPin(tester, '1234');
      await tester.tap(find.text('确认'));
      await tester.pumpAndSettle();
      expect(await result, isTrue);
    });
  });

  group('启动密码页', () {
    late AppLock lock;
    late String password;
    late int lockOuts;
    late PasswordAttempts attempts;

    setUp(() async {
      final platform = FakePlatform();
      await platform.install();
      password = '1234';
      lockOuts = 0;
      lock = AppLock(password: () => password);
      attempts = PasswordAttempts(password: () => password, onLockedOut: () async => lockOuts++);
    });

    Future<void> pumpGate(WidgetTester tester) async {
      await tester.pumpWidget(
        zhApp(
          const Scaffold(body: Text('首页内容')),
          builder: (context, child) => AppLockGate(lock: lock, attempts: attempts, child: child!),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('冷启动先显示密码页，首页不显示；输错留在密码页并说还能试几次，输对才显示首页', (tester) async {
      lock.start();
      await pumpGate(tester);
      expect(find.text('输入 App 密码'), findsOneWidget);
      expect(find.text('不是手机锁屏密码'), findsOneWidget);
      expect(find.text('首页内容'), findsNothing);

      await enterPin(tester, '0000');
      expect(find.text('密码不对，还能试 4 次'), findsOneWidget);
      expect(find.text('首页内容'), findsNothing);
      expect(attempts.failures, 1);

      await enterPin(tester, '1234');
      expect(find.text('输入 App 密码'), findsNothing);
      expect(find.text('首页内容'), findsOneWidget);
      expect(attempts.failures, 0);
    });

    testWidgets('连续输错 5 次：提示即将退出、键盘停用、执行退出一次', (tester) async {
      lock.start();
      await pumpGate(tester);
      for (var i = 0; i < 4; i++) {
        await enterPin(tester, '0000');
      }
      expect(find.text('密码不对，还能试 1 次'), findsOneWidget);
      expect(lockOuts, 0);

      await enterPin(tester, '0000');
      expect(find.text('输错 5 次，App 将退出'), findsOneWidget);
      expect(lockOuts, 1);
      expect(lock.locked, isTrue);

      // 停用了：再按也不会解锁、不会再退一次
      await enterPin(tester, '1234');
      expect(lock.locked, isTrue);
      expect(lockOuts, 1);
    });

    testWidgets('上次输错过（App 重启后）：一打开就提示还能试几次', (tester) async {
      await Preferences.instance.setInt(Preferences.passwordFailures, 3);
      lock.start();
      await pumpGate(tester);
      expect(find.text('还能试 2 次'), findsOneWidget);
      await enterPin(tester, '0000');
      expect(find.text('密码不对，还能试 1 次'), findsOneWidget);
    });

    testWidgets('不到 4 位也能确认（老密码可能更短），一位都没有不能确认', (tester) async {
      password = '12';
      lock.start();
      await pumpGate(tester);
      await tester.tap(find.byIcon(Icons.check));
      await tester.pumpAndSettle();
      expect(attempts.failures, 0);
      await enterPin(tester, '12');
      expect(find.text('首页内容'), findsOneWidget);
    });

    testWidgets('老版本设过带字母的密码：换成输入框，照样计数', (tester) async {
      password = 'abc1';
      lock.start();
      await pumpGate(tester);
      expect(find.byIcon(Icons.backspace_outlined), findsNothing);

      await tester.enterText(find.byType(TextField), 'abc');
      await tester.testTextInput.receiveAction(TextInputAction.done);
      await tester.pumpAndSettle();
      expect(find.text('密码不对，还能试 4 次'), findsOneWidget);

      await tester.enterText(find.byType(TextField), 'abc1');
      await tester.tap(find.text('确认'));
      await tester.pumpAndSettle();
      expect(find.text('首页内容'), findsOneWidget);
    });

    testWidgets('从后台回到前台要再输密码', (tester) async {
      await pumpGate(tester);
      expect(find.text('首页内容'), findsOneWidget);

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
      expect(find.text('输入 App 密码'), findsOneWidget);
      expect(find.text('首页内容'), findsNothing);
    });
  });
}
