// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import '../../lib/password_service.dart';
import '../../lib/preferences.dart';
import '../../lib/setup/password_attempts.dart';
import '../../lib/setup/password_dialog.dart';

import '../support/fake_platform.dart';

/// 设置密码整页和改设置时的密码弹窗：同一套数字键盘。
void main() {
  late FakePlatform platform;

  setUp(() async {
    platform = FakePlatform();
    await platform.install();
  });

  String? stored() => Preferences.instance.getString(Preferences.password);

  /// 首页一个按钮，点了跑 [action]，结果显示成文字。
  Future<void> pumpLauncher(WidgetTester tester, Future<bool> Function(BuildContext) action) async {
    await tester.pumpWidget(
      zhApp(
        Builder(
          builder:
              (context) => Scaffold(
                body: Center(
                  child: StatefulBuilder(
                    builder: (context, setState) {
                      return TextButton(
                        onPressed: () async {
                          final ok = await action(context);
                          setState(() => _result = ok);
                        },
                        child: Text('打开 ${_result ?? ''}'),
                      );
                    },
                  ),
                ),
              ),
        ),
      ),
    );
    _result = null;
    await tester.tap(find.textContaining('打开'));
    await tester.pumpAndSettle();
  }

  group('设置密码', () {
    testWidgets('输两次一样才保存', (tester) async {
      await pumpLauncher(tester, showSetPasswordDialog);
      expect(find.text('设置 App 密码'), findsOneWidget);
      expect(find.text('4 到 12 位数字'), findsOneWidget);
      expect(find.text('清除密码'), findsNothing);

      await enterPin(tester, '2580');
      expect(find.text('再输一次'), findsOneWidget);
      expect(find.text('和刚才输的一样'), findsOneWidget);
      expect(stored(), isNull);

      await enterPin(tester, '2580');
      expect(stored(), storesPassword('2580'));
      expect(find.text('打开 true'), findsOneWidget);
    });

    testWidgets('两次不一样：退回第一步重新设，不保存', (tester) async {
      await pumpLauncher(tester, showSetPasswordDialog);
      await enterPin(tester, '2580');
      await enterPin(tester, '1111');
      expect(find.text('设置 App 密码'), findsOneWidget);
      expect(find.text('两次不一样，请重新设置'), findsOneWidget);
      expect(stored(), isNull);
    });

    testWidgets('不到 4 位按确认没反应', (tester) async {
      await pumpLauncher(tester, showSetPasswordDialog);
      await enterPin(tester, '258');
      expect(find.text('设置 App 密码'), findsOneWidget);
      expect(find.text('再输一次'), findsNothing);
    });

    testWidgets('关掉不保存', (tester) async {
      await pumpLauncher(tester, showSetPasswordDialog);
      await tester.tap(find.byType(CloseButton));
      await tester.pumpAndSettle();
      expect(stored(), isNull);
      expect(find.text('打开 false'), findsOneWidget);
    });
  });

  group('修改密码', () {
    setUp(() => platform.setPassword('1234'));

    testWidgets('先输当前密码，输对后再输两次新密码才换掉', (tester) async {
      await pumpLauncher(tester, showSetPasswordDialog);
      expect(find.text('输入当前密码'), findsOneWidget);
      expect(find.text('验证后再设新密码'), findsOneWidget);
      // 页面上没有不验密码就能清掉的按钮
      expect(find.text('清除密码'), findsNothing);

      await enterPin(tester, '1234');
      expect(find.text('输入新密码'), findsOneWidget);
      expect(find.text('4 到 12 位数字'), findsOneWidget);
      await enterPin(tester, '2580');
      expect(find.text('再输一次'), findsOneWidget);
      expect(stored(), storesPassword('1234'));
      await enterPin(tester, '2580');

      expect(stored(), storesPassword('2580'));
      expect(find.text('打开 true'), findsOneWidget);
      expect(find.text('密码已修改'), findsOneWidget);
    });

    testWidgets('当前密码输错：不进下一步、计入输错次数，密码不变', (tester) async {
      await pumpLauncher(tester, showSetPasswordDialog);
      await enterPin(tester, '0000');
      expect(find.text('密码不对，还能试 4 次'), findsOneWidget);
      expect(find.text('输入当前密码'), findsOneWidget);
      expect(find.text('输入新密码'), findsNothing);
      expect(passwordAttempts.failures, 1);

      await tester.tap(find.byType(CloseButton));
      await tester.pumpAndSettle();
      expect(stored(), storesPassword('1234'));
      expect(find.text('打开 false'), findsOneWidget);
    });

    testWidgets('带字母的老密码：当前密码用输入框，新密码换成数字键', (tester) async {
      await platform.setPassword('abc1');
      await pumpLauncher(tester, showSetPasswordDialog);
      await tester.enterText(find.byType(TextField), 'abc1');
      await tester.tap(find.text('确认'));
      await tester.pumpAndSettle();
      expect(find.text('输入新密码'), findsOneWidget);
      expect(find.byType(TextField), findsNothing);
      await enterPin(tester, '2580');
      await enterPin(tester, '2580');
      expect(stored(), storesPassword('2580'));
    });
  });

  group('关闭密码', () {
    setUp(() => platform.setPassword('1234'));

    testWidgets('输对当前密码才关，输错计数清零', (tester) async {
      await Preferences.instance.setInt(Preferences.passwordFailures, 2);
      await pumpLauncher(tester, showDisablePasswordDialog);
      expect(find.text('关闭 App 密码'), findsOneWidget);
      expect(find.text('还能试 3 次'), findsOneWidget);

      await enterPin(tester, '1234');
      expect(stored(), isNull);
      expect(passwordAttempts.failures, 0);
      expect(find.text('打开 true'), findsOneWidget);
      expect(find.text('App 密码已关闭'), findsOneWidget);
    });

    testWidgets('当前密码输错不关', (tester) async {
      await pumpLauncher(tester, showDisablePasswordDialog);
      expect(find.text('关闭后打开 App 不用密码'), findsOneWidget);
      await enterPin(tester, '9999');
      expect(find.text('密码不对，还能试 4 次'), findsOneWidget);
      expect(stored(), storesPassword('1234'));

      await tester.tap(find.byType(CloseButton));
      await tester.pumpAndSettle();
      expect(stored(), storesPassword('1234'));
      expect(find.text('打开 false'), findsOneWidget);
    });
  });

  group('改设置时的密码弹窗', () {
    setUp(() => platform.setPassword('1234'));

    testWidgets('旧版存过 17 位以上的纯数字密码：能输全、能进（审计 M8）', (tester) async {
      const longPin = '123456789012345678';
      await platform.setPassword(longPin);
      await pumpLauncher(tester, PasswordService.authenticate);
      await enterPin(tester, longPin);
      expect(find.text('输入 App 密码'), findsNothing);
      expect(find.text('打开 true'), findsOneWidget);
    });

    testWidgets('输错留在弹窗并说还能试几次，输对关闭并放行', (tester) async {
      await pumpLauncher(tester, PasswordService.authenticate);
      expect(find.text('输入 App 密码'), findsOneWidget);
      expect(find.text('家长验证后才能继续'), findsOneWidget);

      await enterPin(tester, '0000');
      expect(find.text('密码不对，还能试 4 次'), findsOneWidget);
      expect(find.text('输入 App 密码'), findsOneWidget);

      await enterPin(tester, '1234');
      expect(find.text('输入 App 密码'), findsNothing);
      expect(find.text('打开 true'), findsOneWidget);
      expect(passwordAttempts.failures, 0);
    });

    testWidgets('和启动密码页共用计数：之前错过 3 次，弹窗里再错 2 次就退出', (tester) async {
      await Preferences.instance.setInt(Preferences.passwordFailures, 3);
      await pumpLauncher(tester, PasswordService.authenticate);
      expect(find.text('还能试 2 次'), findsOneWidget);
      await enterPin(tester, '0000');
      expect(find.text('密码不对，还能试 1 次'), findsOneWidget);
      // 第 5 次走真的退出流程：没配服务器不上报，只退回桌面（假的平台通道里记下）
      final calls = <String>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        calls.add(call.method);
        return null;
      });
      addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));
      await enterPin(tester, '0000');
      expect(find.text('输错 5 次，App 将退出'), findsOneWidget);
      expect(calls, contains('SystemNavigator.pop'));
    });

    testWidgets('关掉弹窗不放行', (tester) async {
      await pumpLauncher(tester, PasswordService.authenticate);
      await tester.tap(find.byType(CloseButton));
      await tester.pumpAndSettle();
      expect(find.text('打开 false'), findsOneWidget);
    });
  });
}

bool? _result;
