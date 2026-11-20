// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'package:flutter_test/flutter_test.dart';
import '../../lib/setup/last_report.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

void main() {
  final now = DateTime(2026, 11, 15, 12);
  int minutesAgo(int m) => now.subtract(Duration(minutes: m)).millisecondsSinceEpoch;
  LogEntry log(int m, String message) => LogEntry(time: minutesAgo(m), message: message);

  ReportStatus status(List<LogEntry> logs, {bool tracking = true, int heartbeat = 180}) =>
      reportStatus(logs, tracking: tracking, heartbeatSeconds: heartbeat, now: now);

  test('取最近一次 2xx 上报，失败和非 2xx 不算', () {
    final r = status([
      log(30, 'Upload response 200'),
      log(20, 'Upload response 204'),
      log(10, 'Upload response 400'),
      log(5, 'Upload error: timeout'),
    ]);
    expect(r.lastSuccess, DateTime.fromMillisecondsSinceEpoch(minutesAgo(20)));
  });

  test('超过 3 个心跳周期没成功上报就超时，没到不算', () {
    expect(status([log(10, 'Upload response 200')]).overdue, isTrue);
    expect(status([log(8, 'Upload response 200')]).overdue, isFalse);
  });

  test('刚开始跟踪还没报出去，从开始跟踪算起', () {
    final logs = [log(60, 'Upload response 200'), log(2, 'Tracker start https://x 1')];
    expect(status(logs).overdue, isFalse);
    expect(status(logs).lastSuccess, DateTime.fromMillisecondsSinceEpoch(minutesAgo(60)));
    expect(status([log(10, 'Tracker start https://x 1')]).overdue, isTrue);
  });

  test('没在跟踪、心跳关掉、没有日志时都不判超时', () {
    final old = [log(600, 'Upload response 200')];
    expect(status(old, tracking: false).overdue, isFalse);
    expect(status(old, heartbeat: 0).overdue, isFalse);
    final empty = status(const []);
    expect(empty.overdue, isFalse);
    expect(empty.lastSuccess, isNull);
  });
}
