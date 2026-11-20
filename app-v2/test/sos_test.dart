// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import '../lib/l10n/app_localizations.dart';
import '../lib/sos.dart';

/// 求救（R5）：发送中、发出去、没发出去都让孩子看到；没发出去留在界面可以再发（审计 M3）。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  group('sendSos 和原生结果对应', () {
    const channel = MethodChannel('traccar_client_sdk');
    final calls = <String>[];

    void answer(Object? Function(MethodCall call) handler) {
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, (call) async {
        calls.add(call.method);
        return handler(call);
      });
    }

    tearDown(() {
      calls.clear();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger.setMockMethodCallHandler(channel, null);
    });

    test('服务器收到了', () async {
      answer((_) => {'id': 7, 'sent': true});
      final result = await sendSos(null);
      expect(result.outcome, SosOutcome.sent);
      expect(result.id, 7);
      expect(calls, ['sendSos']);
    });

    test('没收到：存在手机上等补发；再发带上那条的编号', () async {
      final args = <Object?>[];
      answer((call) {
        args.add(call.arguments);
        return {'id': 7, 'sent': false};
      });
      expect((await sendSos(null)).outcome, SosOutcome.queued);
      await sendSos(7);
      expect(args, [
        {'retry': null},
        {'retry': 7},
      ]);
    });

    test('问补发出去没有', () async {
      answer((call) => (call.arguments as Map)['id'] == 7);
      expect(await isSosSent(7), isTrue);
      expect(await isSosSent(8), isFalse);
      expect(calls, ['isAlertSent', 'isAlertSent']);
    });

    test('原生出错', () async {
      answer((_) => throw PlatformException(code: 'IllegalStateException', message: 'Tracker not configured'));
      final result = await sendSos(null);
      expect(result.outcome, SosOutcome.error);
      expect(result.detail, 'Tracker not configured');
    });
  });

  group('求救界面', () {
    late List<Completer<SosResult>> pending;
    late List<int?> retries;
    late SosController controller;
    late int exits;
    late bool delivered;

    setUp(() {
      pending = [];
      retries = [];
      exits = 0;
      delivered = false;
      controller = SosController(
        send: (retry) {
          retries.add(retry);
          final c = Completer<SosResult>();
          pending.add(c);
          return c.future;
        },
        isSent: (id) async => delivered,
      );
    });

    tearDown(() => controller.dispose());

    Future<void> pumpLayer(WidgetTester tester) => tester.pumpWidget(MaterialApp(
          locale: const Locale('zh'),
          localizationsDelegates: AppLocalizations.localizationsDelegates,
          supportedLocales: AppLocalizations.supportedLocales,
          builder: (context, child) => SosLayer(
            controller: controller,
            exitDelay: const Duration(seconds: 3),
            onExit: () async => exits++,
            child: child!,
          ),
          home: const Scaffold(body: Text('首页')),
        ));

    testWidgets('没发出去：写清会自动补发，留在界面，可以再发', (tester) async {
      await pumpLayer(tester);
      unawaited(controller.send(exitWhenSent: true));
      await tester.pump();
      expect(find.text('正在发送…'), findsOneWidget);

      pending.last.complete(const SosResult(SosOutcome.queued, id: 1));
      await tester.pump();
      expect(find.text('求救还没发出去，联网后会自动补发。可以再发一次，或直接给家长打电话'), findsOneWidget);
      expect(find.text('再发一次求救'), findsOneWidget);
      // 没发出去不退回桌面
      await tester.pump(const Duration(seconds: 10));
      expect(exits, 0);
      expect(controller.active, isTrue);

      await tester.tap(find.text('再发一次求救'));
      await tester.pump();
      expect(pending, hasLength(2), reason: '再发一次就是再调一次发送');
      expect(retries, [null, 1], reason: '再发带着上次那条的编号，不另起一条');
      expect(find.text('正在发送…'), findsOneWidget);

      pending.last.complete(const SosResult(SosOutcome.sent, id: 1));
      await tester.pump();
      expect(find.text('求救已发给家长'), findsOneWidget);
      expect(find.text('再发一次求救'), findsNothing);
      await tester.pump(const Duration(seconds: 3));
      expect(exits, 1, reason: '桌面快捷进来的，发出去后退回桌面');
      expect(controller.active, isFalse);
    });

    testWidgets('首页按的发出去后只关界面，不退回桌面', (tester) async {
      await pumpLayer(tester);
      unawaited(controller.send());
      await tester.pump();
      pending.last.complete(const SosResult(SosOutcome.sent, id: 1));
      await tester.pump();
      await tester.pump(const Duration(seconds: 3));
      expect(exits, 0);
      expect(controller.active, isFalse);
      expect(find.text('首页'), findsOneWidget);
    });

    testWidgets('出错写原因，可以关掉', (tester) async {
      await pumpLayer(tester);
      unawaited(controller.send());
      await tester.pump();
      pending.last.complete(const SosResult(SosOutcome.error, detail: 'Tracker not configured'));
      await tester.pump();
      expect(find.text('没发出去：Tracker not configured'), findsOneWidget);
      await tester.tap(find.text('关闭'));
      await tester.pump();
      expect(controller.active, isFalse);
      expect(find.text('再发一次求救'), findsNothing);
    });

    testWidgets('后台联网补发出去了，界面自己改成已发给家长', (tester) async {
      await pumpLayer(tester);
      unawaited(controller.send(exitWhenSent: true));
      await tester.pump();
      pending.last.complete(const SosResult(SosOutcome.queued, id: 1));
      await tester.pump();
      await tester.pump(const Duration(seconds: 5));
      expect(find.text('再发一次求救'), findsOneWidget);

      delivered = true;
      await tester.pump(const Duration(seconds: 5));
      await tester.pump();
      expect(find.text('求救已发给家长'), findsOneWidget);
      expect(pending, hasLength(1), reason: '不用再发');
      await tester.pump(const Duration(seconds: 3));
      expect(exits, 1);
    });

    testWidgets('关掉再开是新的一次', (tester) async {
      await pumpLayer(tester);
      unawaited(controller.send());
      await tester.pump();
      pending.last.complete(const SosResult(SosOutcome.queued, id: 1));
      await tester.pump();
      await tester.tap(find.text('关闭'));
      await tester.pump();
      unawaited(controller.send());
      await tester.pump();
      expect(retries, [null, null]);
      pending.last.complete(const SosResult(SosOutcome.sent, id: 1));
      await tester.pump(const Duration(seconds: 3));
    });

    testWidgets('发送中再按不会重复发', (tester) async {
      await pumpLayer(tester);
      unawaited(controller.send());
      unawaited(controller.send());
      await tester.pump();
      expect(pending, hasLength(1));
      pending.last.complete(const SosResult(SosOutcome.sent, id: 1));
      await tester.pump(const Duration(seconds: 3));
    });
  });
}
