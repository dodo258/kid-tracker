import 'package:flutter/services.dart';

/// Location-accuracy preset. Maps to the SDK's `Accuracy` enum.
enum Accuracy { highest, high, medium, low }

/// Tuning parameters for the location pipeline.
class LocationConfig {
  const LocationConfig({
    this.accuracy = Accuracy.medium,
    this.distanceMeters = 75,
    this.intervalSeconds = 300,
    this.angleDegrees = 0,
    this.stopDetection = true,
    this.stopTimeoutSeconds = 60,
    this.stationaryRadiusMeters = 100,
    this.heartbeatIntervalSeconds = 0,
  });

  final Accuracy accuracy;
  final int distanceMeters;
  final int intervalSeconds;
  final int angleDegrees;
  final bool stopDetection;
  final int stopTimeoutSeconds;
  final int stationaryRadiusMeters;
  final int heartbeatIntervalSeconds;

  Map<String, Object?> _toMap() => {
        'accuracy': accuracy.name.toUpperCase(),
        'distanceMeters': distanceMeters,
        'intervalSeconds': intervalSeconds,
        'angleDegrees': angleDegrees,
        'stopDetection': stopDetection,
        'stopTimeoutSeconds': stopTimeoutSeconds,
        'stationaryRadiusMeters': stationaryRadiusMeters,
        'heartbeatIntervalSeconds': heartbeatIntervalSeconds,
      };
}

/// Foreground-service notification settings (Android only).
class NotificationConfig {
  const NotificationConfig({this.text = 'Location tracking'});

  final String text;

  Map<String, Object?> _toMap() => {'text': text};
}

/// Tracker configuration. Pass to [TraccarClientSdk.setConfig] to install or
/// update.
class Config {
  const Config({
    required this.serverUrl,
    this.headers = const {},
    required this.deviceId,
    this.location = const LocationConfig(),
    this.wakeLock = false,
    this.buffer = true,
    this.preferPlatformProviders = false,
    this.notification = const NotificationConfig(),
  });

  final String serverUrl;

  /// Additional HTTP headers, including Authorization. Persisted with the config.
  final Map<String, String> headers;

  final String deviceId;
  final LocationConfig location;

  /// Hold a wakelock while tracking (Android only).
  final bool wakeLock;

  /// When true, persist positions to a local queue and retry on network
  /// failure. When false, attempt a direct upload for each position and
  /// drop it on failure (real-time only).
  final bool buffer;

  /// When true, the Android SDK uses the platform `LocationManager` directly
  /// even when Google Play Services is available. Default `false` picks the
  /// Fused Location Provider when Play Services is present. Ignored on iOS.
  final bool preferPlatformProviders;

  final NotificationConfig notification;

  Map<String, Object?> _toMap() => {
        'serverUrl': serverUrl,
        'headers': headers,
        'deviceId': deviceId,
        'location': location._toMap(),
        'wakeLock': wakeLock,
        'buffer': buffer,
        'preferPlatformProviders': preferPlatformProviders,
        'notification': notification._toMap(),
      };
}

/// A single diagnostic log entry.
class LogEntry {
  const LogEntry({required this.time, required this.message});

  /// Epoch milliseconds at which the entry was recorded.
  final int time;
  final String message;
}

/// 一次求救：哪一条（本机告警编号）、服务器收到没有。
class SosSend {
  const SosSend({required this.id, required this.sent});

  final int id;
  final bool sent;
}

/// 自检清单里厂商设置的证据：重启后自己恢复、锁屏时呼吸准不准、锁屏时能不能发出去。
enum HealthEvidence { reboot, breath, send }

/// 待验证、已生效、没生效。
enum HealthVerdict { pending, ok, bad }

/// 一项证据的结论；[detail] 是重启后几秒恢复（reboot）、最近一次迟到几分钟（breath）。
class HealthJudgement {
  const HealthJudgement(this.verdict, [this.detail]);

  final HealthVerdict verdict;
  final int? detail;
}

/// 异常的种类（sdk/core Log.kt 的 Fault）：看门狗出手、降级（高德切系统定位）、写不进存储、满了丢最旧的、组件出错。
enum FaultKind { rescue, degraded, storage, dropped, crash }

/// 最近一次异常：什么时候、哪一类、说明（英文，给排查用）。
class LastFault {
  const LastFault({required this.time, required this.kind, required this.message});

  final DateTime time;
  final FaultKind kind;
  final String message;
}

/// Entry point for the Traccar Client SDK Flutter plugin.
class TraccarClientSdk {
  static const MethodChannel _channel = MethodChannel('traccar_client_sdk');

  /// Initializes the SDK with [config] if it isn't already initialized.
  /// Idempotent — subsequent calls return the existing tracker without
  /// touching its config. Call once at app startup to seed defaults; use
  /// [setConfig] to change settings on a running tracker.
  Future<void> init(Config config) =>
      _channel.invokeMethod<void>('init', config._toMap());

  /// Replaces the running tracker's configuration with [config]. Requires
  /// that [init] (or a prior session) has installed a tracker. Throws a
  /// [PlatformException] otherwise.
  Future<void> setConfig(Config config) =>
      _channel.invokeMethod<void>('setConfig', config._toMap());

  /// Starts background location tracking. Requires that the SDK has been
  /// initialized (via [init] in this session, or via persisted config from
  /// a previous one). Throws a [PlatformException] if required permissions
  /// were denied or no config has ever been provided.
  Future<void> start() => _channel.invokeMethod<void>('start');

  /// Stops tracking.
  Future<void> stop() => _channel.invokeMethod<void>('stop');

  /// Requests a single position fix and uploads it to the server. Returns
  /// whether the upload succeeded. Works independently of [start] / [stop].
  /// Requires that [setConfig] has been called.
  ///
  /// Prompts for location permission if not already granted and throws a
  /// [PlatformException] when permission is denied (matching [start]). A
  /// `false` return means a fix or upload failure, not a permission problem.
  ///
  /// Pass [alarm] (e.g. `"sos"`) to tag the upload with the Traccar `alarm`
  /// protocol field. The one-off path does not buffer — a failed upload is
  /// not retried and the alarm is lost.
  Future<bool> requestPosition({String? alarm}) async {
    final result = await _channel.invokeMethod<bool>(
      'requestPosition',
      {'alarm': alarm},
    );
    return result ?? false;
  }

  /// 求救：先存在手机上（还有没发出去的求救就重发那一条，不另起一条），有定位权限时现取一次位置、只带新鲜的卫星点，
  /// 然后发一次。返回这条求救的编号和服务器收到没有（回了 2xx）：没收到的留着，联网后 SDK 自动补发。
  /// 界面上「再发一次」传上次的 [retry] 编号：只重发那一条，已经补发出去了就直接返回已收到。
  /// 不弹定位权限框；追踪开没开都能用。没有配置时抛 [PlatformException]。
  Future<SosSend> sendSos({int? retry}) async {
    final result = await _channel.invokeMapMethod<String, Object?>('sendSos', {'retry': retry});
    return SosSend(id: result?['id'] as int, sent: result?['sent'] == true);
  }

  /// 这条告警（[sendSos] 返回的编号）服务器收到了没有。
  Future<bool> isAlertSent(int id) async {
    final result = await _channel.invokeMethod<bool>('isAlertSent', {'id': id});
    return result ?? false;
  }

  /// 防护被动了（`adminDisabled`、`passwordFailures`）：报一条 alarm=tampering，先存在手机上再发，联网后补发。
  /// 没有配置时抛 [PlatformException]。
  Future<void> reportTampering(String reason) =>
      _channel.invokeMethod<void>('reportTampering', {'reason': reason});

  /// 厂商设置有没有生效（按实际表现判，见 sdk/core health/BackgroundHealth.kt）；每次调用顺带补判没响的闹钟、重启后没自启。
  Future<Map<HealthEvidence, HealthJudgement>> getHealth() async {
    final raw = await _channel.invokeMapMethod<String, Object?>('getHealth') ?? const {};
    return {
      for (final evidence in HealthEvidence.values)
        evidence: switch (raw[evidence.name]) {
          {'verdict': final String verdict, 'detail': final Object? detail} => HealthJudgement(
              HealthVerdict.values.byName(verdict),
              detail is int ? detail : null,
            ),
          _ => const HealthJudgement(HealthVerdict.pending),
        },
    };
  }

  /// 最近一次异常（降级、重建、丢弃、写不进存储）；没有记录是 null。
  Future<LastFault?> getLastFault() async {
    final raw = await _channel.invokeMapMethod<String, Object?>('getLastFault');
    if (raw == null) return null;
    return LastFault(
      time: DateTime.fromMillisecondsSinceEpoch(raw['time'] as int),
      kind: FaultKind.values.asNameMap()[raw['kind']] ?? FaultKind.crash,
      message: raw['message'] as String? ?? '',
    );
  }

  /// 家长改过这项的设置：重新验证。
  Future<void> resetHealth(HealthEvidence evidence) =>
      _channel.invokeMethod<void>('resetHealth', {'evidence': evidence.name});

  /// Returns whether tracking is currently active.
  Future<bool> isTracking() async {
    final result = await _channel.invokeMethod<bool>('isTracking');
    return result ?? false;
  }

  /// Returns recent diagnostic entries, oldest first.
  Future<List<LogEntry>> getLogs() async {
    final raw =
        await _channel.invokeListMethod<Map<dynamic, dynamic>>('getLogs');
    if (raw == null) return const [];
    return raw
        .map((m) => LogEntry(
              time: m['time'] as int,
              message: m['message'] as String,
            ))
        .toList(growable: false);
  }

  /// Clears all stored diagnostic entries.
  Future<void> clearLogs() => _channel.invokeMethod<void>('clearLogs');
}
