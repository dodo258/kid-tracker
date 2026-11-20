import 'package:traccar_client_sdk/traccar_client_sdk.dart';

/// SDK 每次上报后记一条 `Upload response <HTTP 状态码>`（sdk/core HttpUploader.kt），2xx 才算成功。
final uploadSuccessPattern = RegExp(r'^Upload response 2\d\d$');

class ReportStatus {
  const ReportStatus({required this.lastSuccess, required this.overdue});

  final DateTime? lastSuccess;

  /// 在跟踪，且从上次成功上报（或开始跟踪）起超过 3 个心跳周期没报出去。
  final bool overdue;
}

/// 从 SDK 诊断日志算上次成功上报时间；刚开始跟踪还没报出去的，从开始跟踪算起。
/// 心跳关掉（0）时没有基准周期，不判超时；日志被清空时无从判断，也不判。
ReportStatus reportStatus(
  List<LogEntry> logs, {
  required bool tracking,
  required int heartbeatSeconds,
  required DateTime now,
}) {
  int? lastSuccess;
  int? lastStart;
  for (final entry in logs) {
    if (uploadSuccessPattern.hasMatch(entry.message)) {
      if (lastSuccess == null || entry.time > lastSuccess) lastSuccess = entry.time;
    } else if (entry.message.startsWith('Tracker start')) {
      if (lastStart == null || entry.time > lastStart) lastStart = entry.time;
    }
  }
  final since = (lastSuccess == null || (lastStart != null && lastStart > lastSuccess)) ? lastStart : lastSuccess;
  final overdue = tracking &&
      heartbeatSeconds > 0 &&
      since != null &&
      now.millisecondsSinceEpoch - since > heartbeatSeconds * 3 * 1000;
  return ReportStatus(
    lastSuccess: lastSuccess == null ? null : DateTime.fromMillisecondsSinceEpoch(lastSuccess),
    overdue: overdue,
  );
}
