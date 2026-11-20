import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:package_info_plus/package_info_plus.dart';

import '../preferences.dart';

/// 服务器上 latest.json 描述的最新版本。
class UpdateInfo {
  const UpdateInfo({
    required this.versionCode,
    required this.versionName,
    required this.apkUrl,
    required this.sha256,
    required this.notes,
  });

  final int versionCode;
  final String versionName;
  final Uri apkUrl;

  /// apk 的 SHA-256，64 位小写十六进制；下完核对，不符不装。
  final String sha256;

  /// 更新说明，一句话，可以为空。
  final String notes;

  Map<String, Object> toJson() => {
    'versionCode': versionCode,
    'versionName': versionName,
    'apkUrl': apkUrl.toString(),
    'sha256': sha256,
    'notes': notes,
  };
}

/// 解析 latest.json：`{"versionCode": 169, "versionName": "10.2.5", "apkUrl": "https://…/x.apk", "sha256": "…", "notes": "一句话"}`。
/// versionCode 必须是正整数（数字字符串也认）；apkUrl（也认 apk）可以写相对 [source] 的路径，最终只认 http/https；
/// sha256 必须是 64 位十六进制（大小写都认）：没有它就没法核对下载的安装包，当没有新版本。
/// 格式不对返回 null，不抛错。
UpdateInfo? parseUpdateInfo(String body, Uri source) {
  final Object? data;
  try {
    data = jsonDecode(body);
  } on FormatException {
    return null;
  }
  if (data is! Map<String, dynamic>) return null;
  final code = switch (data['versionCode']) {
    int v => v,
    String v => int.tryParse(v.trim()),
    _ => null,
  };
  if (code == null || code <= 0) return null;
  final link = data['apkUrl'] ?? data['apk'];
  if (link is! String || link.trim().isEmpty) return null;
  final Uri apk;
  try {
    apk = source.resolve(link.trim());
  } on FormatException {
    return null;
  }
  if ((apk.scheme != 'https' && apk.scheme != 'http') || apk.host.isEmpty) return null;
  final hash = data['sha256'];
  if (hash is! String || !_sha256Pattern.hasMatch(hash.trim())) return null;
  final name = data['versionName'];
  final notes = data['notes'];
  return UpdateInfo(
    versionCode: code,
    versionName: name is String ? name.trim() : '',
    apkUrl: apk,
    sha256: hash.trim().toLowerCase(),
    notes: notes is String ? notes.trim() : '',
  );
}

final _sha256Pattern = RegExp(r'^[0-9a-fA-F]{64}$');

/// 服务器上的版本比本机新才提示：只比 versionCode，versionName 只是给人看的。
bool isNewer(UpdateInfo info, int currentVersionCode) => info.versionCode > currentVersionCode;

/// 一天查一次：从没查过、上次成功检查超过 24 小时、或系统时间被往回调过，都该查了。
bool checkDue(DateTime? lastCheck, DateTime now) {
  if (lastCheck == null || now.isBefore(lastCheck)) return true;
  return now.difference(lastCheck) >= const Duration(days: 1);
}

/// 检查更新：前台打开时调用 [available]，到期才联网拿 latest.json，结果存在设置里，没到期就用存着的。
/// 联网失败、服务器出错、内容不对都静默：不提示、不记检查时间，下次回到前台再试。
class AppUpdate {
  AppUpdate({
    Uri? latestUrl,
    Future<String> Function(Uri url)? fetch,
    Future<int> Function()? currentVersionCode,
    DateTime Function()? now,
  }) : latestUrl = latestUrl ?? Uri.parse(defaultLatestUrl),
       _fetch = fetch ?? fetchText,
       _currentVersionCode = currentVersionCode ?? _installedVersionCode,
       _now = now ?? DateTime.now;

  static const productionLatestUrl = 'https://gps.example.com/app/latest.json';

  /// 发布地址；模拟器测试用 `--dart-define=UPDATE_URL=http://10.0.2.2:<端口>/latest.json` 指到本机假文件。
  /// 正式包（release）不认这个开关，只查正式地址：测试地址打进正式包后，手机就再也收不到正式更新了（审计 M17）。
  static const defaultLatestUrl = kReleaseMode
      ? productionLatestUrl
      : String.fromEnvironment('UPDATE_URL', defaultValue: productionLatestUrl);

  final Uri latestUrl;
  final Future<String> Function(Uri url) _fetch;
  final Future<int> Function() _currentVersionCode;
  final DateTime Function() _now;
  Future<UpdateInfo?>? _running;

  /// 有比本机新的版本就返回它，否则 null。同时只跑一次，失败返回 null。
  Future<UpdateInfo?> available() => _running ??= _available().whenComplete(() => _running = null);

  Future<UpdateInfo?> _available() async {
    try {
      final current = await _currentVersionCode();
      final checkedAt = Preferences.instance.getInt(Preferences.updateCheckedAt);
      final last = checkedAt == null ? null : DateTime.fromMillisecondsSinceEpoch(checkedAt);
      if (checkDue(last, _now())) {
        try {
          await _check();
        } catch (_) {
          // 联网失败静默，继续用上次查到的结果
        }
      }
      final stored = _stored();
      return stored != null && isNewer(stored, current) ? stored : null;
    } catch (_) {
      return null; // 拿不到本机版本号等：静默
    }
  }

  Future<void> _check() async {
    final info = parseUpdateInfo(await _fetch(latestUrl), latestUrl);
    if (info == null) return;
    await Preferences.instance.setString(Preferences.updateInfo, jsonEncode(info.toJson()));
    await Preferences.instance.setInt(Preferences.updateCheckedAt, _now().millisecondsSinceEpoch);
  }

  UpdateInfo? _stored() {
    final json = Preferences.instance.getString(Preferences.updateInfo);
    return json == null ? null : parseUpdateInfo(json, latestUrl);
  }

  static Future<int> _installedVersionCode() async =>
      int.tryParse((await PackageInfo.fromPlatform()).buildNumber) ?? 0;
}

/// GET 文本：连上 10 秒、整个请求 20 秒超时，只认 200，最多读 64KB。
Future<String> fetchText(Uri url) async {
  final client = HttpClient()..connectionTimeout = const Duration(seconds: 10);
  try {
    return await () async {
      final request = await client.getUrl(url);
      final response = await request.close();
      if (response.statusCode != HttpStatus.ok) {
        await response.drain<void>();
        throw HttpException('HTTP ${response.statusCode}', uri: url);
      }
      final bytes = <int>[];
      await for (final chunk in response) {
        bytes.addAll(chunk);
        if (bytes.length > 64 * 1024) throw HttpException('response too large', uri: url);
      }
      return utf8.decode(bytes);
    }().timeout(const Duration(seconds: 20));
  } finally {
    client.close(force: true);
  }
}

/// 首页用的检查器；测试换成注入了假网络和假版本号的。
var appUpdate = AppUpdate();
