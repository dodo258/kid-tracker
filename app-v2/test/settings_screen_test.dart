// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/settings_screen.dart';

import 'support/fake_platform.dart';

/// 设置页（R6，沿用旧 App）：设备号、服务器；高级设置里是清单、防卸载、技术选项、测试用录制；App 密码一直看得到。
void main() {
  late FakePlatform platform;

  setUp(() async {
    platform = FakePlatform();
    await platform.install();
  });

  Future<void> pumpSettings(WidgetTester tester, {bool advanced = false}) async {
    tester.view.physicalSize = const Size(800, 2600);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(zhApp(const SettingsScreen()));
    await tester.pumpAndSettle();
    if (advanced) {
      await tester.tap(find.text('高级设置'));
      await tester.pumpAndSettle();
    }
  }

  testWidgets('判断层下不起作用的精度、距离、间隔、心跳不再列', (tester) async {
    await pumpSettings(tester, advanced: true);
    expect(find.text('设备标识符'), findsWidgets);
    expect(find.text('服务器地址'), findsWidgets);
    for (final gone in ['定位精度', '距离', '间隔', '心跳']) {
      expect(find.textContaining(gone), findsNothing, reason: gone);
    }
    expect(find.byIcon(Icons.qr_code_scanner), findsOneWidget);
  });

  testWidgets('高级设置：清单、防卸载、技术选项、测试用录制', (tester) async {
    await pumpSettings(tester, advanced: true);
    expect(find.text('稳定运行需要的设置'), findsOneWidget);
    expect(find.text('防卸载（设备管理员）'), findsOneWidget);
    expect(find.text('技术选项（不用动）'), findsOneWidget);
    expect(find.text('传感器录制（测试用）'), findsOneWidget);
    expect(find.textContaining('共 2 个文件 3.0 MB'), findsOneWidget);
  });

  testWidgets('录制出错停下：显示原因', (tester) async {
    platform.recorderError = 'write: No space left on device';
    await pumpSettings(tester, advanced: true);
    expect(find.textContaining('录制停了：write: No space left on device'), findsOneWidget);
  });

  testWidgets('关防卸载要输 App 密码，输对才停用', (tester) async {
    await platform.setPassword('1234');
    platform.wantAdmin = true;
    await pumpSettings(tester, advanced: true);
    await tester.tap(find.text('防卸载（设备管理员）'));
    await tester.pumpAndSettle();
    expect(find.text('输入 App 密码'), findsOneWidget);
    expect(platform.admin, isTrue);
    await enterPin(tester, '1234');
    await tester.pump(const Duration(seconds: 1));
    await tester.pumpAndSettle();
    expect(platform.admin, isFalse);
  });

  testWidgets('开防卸载：没有 App 密码先设一个', (tester) async {
    platform.admin = false;
    await pumpSettings(tester, advanced: true);
    await tester.tap(find.text('防卸载（设备管理员）'));
    await tester.pumpAndSettle();
    expect(find.text('设置 App 密码'), findsWidgets);
    expect(platform.admin, isFalse);
  });
}
