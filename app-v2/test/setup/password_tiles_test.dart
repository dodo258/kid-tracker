// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';
import '../../lib/preferences.dart';
import '../../lib/settings_screen.dart';

import '../support/fake_platform.dart';

/// 设置页的 App 密码入口：不用打开高级设置就能看到。
void main() {
  late FakePlatform platform;

  setUp(() async {
    platform = FakePlatform();
    await platform.install();
  });

  /// 设置页里 App 密码在列表最下面：屏幕放高，整页都在可见区域里。
  Future<void> pumpSettings(WidgetTester tester) async {
    tester.view.physicalSize = const Size(800, 2000);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(zhApp(const SettingsScreen()));
    await tester.pumpAndSettle();
  }

  testWidgets('没设密码：一行「App 密码 · 未设置」，点了去设置', (tester) async {
    await pumpSettings(tester);
    expect(find.text('未设置'), findsOneWidget);
    expect(find.text('修改密码'), findsNothing);

    await tester.tap(find.text('App 密码'));
    await tester.pumpAndSettle();
    await enterPin(tester, '2580');
    await enterPin(tester, '2580');
    expect(Preferences.instance.getString(Preferences.password), storesPassword('2580'));
    expect(find.text('修改密码'), findsOneWidget);
    expect(find.text('关闭密码'), findsOneWidget);
  });

  testWidgets('设了密码：「关闭密码」输对当前密码后回到「未设置」', (tester) async {
    await platform.setPassword('1234');
    await pumpSettings(tester);

    await tester.tap(find.text('关闭密码'));
    await tester.pumpAndSettle();
    expect(find.text('关闭 App 密码'), findsOneWidget);
    await enterPin(tester, '1234');
    expect(Preferences.instance.getString(Preferences.password), isNull);
    expect(find.text('未设置'), findsOneWidget);
  });

  testWidgets('设了密码：「修改密码」要先输当前密码', (tester) async {
    await platform.setPassword('1234');
    await pumpSettings(tester);

    await tester.tap(find.text('修改密码'));
    await tester.pumpAndSettle();
    expect(find.text('输入当前密码'), findsOneWidget);
    await enterPin(tester, '1111');
    expect(find.text('输入新密码'), findsNothing);
    await enterPin(tester, '1234');
    await enterPin(tester, '9753');
    await enterPin(tester, '9753');
    expect(Preferences.instance.getString(Preferences.password), storesPassword('9753'));
  });
}
