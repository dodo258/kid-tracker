import 'dart:async';
import 'dart:io';
import 'dart:isolate';

import 'package:crypto/crypto.dart';
import 'package:flutter/services.dart';

import 'app_update.dart';

/// 下载为什么没成：网络（连不上、服务器出错、超时、文件过大）或下到的安装包和 latest.json 里的 sha256 对不上。
enum DownloadFailure { network, checksum }

class ApkDownloadException implements Exception {
  const ApkDownloadException(this.failure, this.cause);

  final DownloadFailure failure;
  final Object cause;

  @override
  String toString() => 'ApkDownloadException(${failure.name}): $cause';
}

/// 开始下载：返回内容长度（服务器没给就是 null）和字节流。
typedef OpenDownload = Future<({int? length, Stream<List<int>> body})> Function(Uri url);

/// App 内更新：把新版 apk 下到 App 缓存目录的 update/ 里、核对 SHA-256，再交给系统安装器。
/// 下载和校验在 Dart 里做；缓存目录、「安装未知应用」权限、弹安装确认走原生 [channel]（UpdateChannel.kt）。
class ApkUpdater {
  ApkUpdater({OpenDownload? open, MethodChannel? channel})
      : _open = open ?? _httpOpen,
        _channel = channel ?? const MethodChannel('com.dodo258.tracker/update');

  /// 安装包大小上限，防止地址写错下到别的大文件把存储占满。
  static const maxBytes = 200 * 1024 * 1024;

  final OpenDownload _open;
  final MethodChannel _channel;

  /// 下载 [info] 的 apk 到 `update/<versionCode>.apk`，[onProgress] 报 0~1（不知道总长时报 null）。
  /// 已经下好且校验对的直接用；校验不符删掉文件，抛 [DownloadFailure.checksum]；其他失败抛 [DownloadFailure.network]。
  Future<File> download(UpdateInfo info, {void Function(double? progress)? onProgress}) async {
    final dir = await _dir();
    final target = File('${dir.path}/${info.versionCode}.apk');
    if (await target.exists() && await _sha256Of(target) == info.sha256) return target;

    await clear();
    await dir.create(recursive: true);
    final part = File('${target.path}.part');
    try {
      await _save(info.apkUrl, part, onProgress);
    } catch (e) {
      await _delete(part);
      throw ApkDownloadException(DownloadFailure.network, e);
    }
    final actual = await _sha256Of(part);
    if (actual != info.sha256) {
      await _delete(part);
      throw ApkDownloadException(DownloadFailure.checksum, 'sha256 $actual != ${info.sha256}');
    }
    return part.rename(target.path);
  }

  Future<void> _save(Uri url, File part, void Function(double? progress)? onProgress) async {
    final (:length, :body) = await _open(url);
    if (length != null && length > maxBytes) throw const FileSystemException('apk too large');
    final sink = part.openWrite();
    try {
      var received = 0;
      onProgress?.call(length == null ? null : 0);
      await for (final chunk in body) {
        received += chunk.length;
        if (received > maxBytes) throw const FileSystemException('apk too large');
        sink.add(chunk);
        onProgress?.call(length == null || length == 0 ? null : (received / length).clamp(0.0, 1.0));
      }
      if (length != null && received != length) throw FileSystemException('incomplete: $received of $length');
    } finally {
      await sink.close();
    }
  }

  /// 「安装未知应用」是否已允许本 App（安卓 8 以下不需要）。
  Future<bool> canInstall() => _call('canInstall');

  /// 跳到本 App 的「安装未知应用」开关页；打不开返回 false。
  Future<bool> openInstallSettings() => _call('openInstallSettings');

  /// 核对安装包身份（原生 UpdateChannel）：本 App 的包名、和装着的同一张签名证书、版本号更大。
  /// 对了返回 null；不对返回原因（package、signature、version、unreadable）。
  Future<String?> verify(File apk) async {
    try {
      return await _channel.invokeMethod<String>('verify', {'path': apk.path});
    } on PlatformException catch (e) {
      return e.code;
    } on MissingPluginException {
      return 'unreadable';
    }
  }

  /// 弹系统安装确认；文件不在了、身份不对或没有安装器返回 false。
  Future<bool> install(File apk) => _call('install', {'path': apk.path});

  /// 删掉下载过的安装包（装好以后、开始新下载前）。
  Future<void> clear() async {
    try {
      final dir = await _dir();
      if (await dir.exists()) await dir.delete(recursive: true);
    } catch (_) {
      // 缓存删不掉不影响使用，系统空间紧张时也会清
    }
  }

  Future<bool> _call(String method, [Object? arguments]) async {
    try {
      return await _channel.invokeMethod<bool>(method, arguments) ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  Future<Directory> _dir() async {
    final cache = await _channel.invokeMethod<String>('cacheDir');
    if (cache == null) throw const FileSystemException('no cache dir');
    return Directory('$cache/update');
  }

  /// 在后台 isolate 里算：安装包几十 MB，放在界面线程算会卡住进度条。
  static Future<String> _sha256Of(File file) {
    final path = file.path;
    return Isolate.run(() async => (await sha256.bind(File(path).openRead()).first).toString());
  }

  static Future<void> _delete(File file) async {
    try {
      if (await file.exists()) await file.delete();
    } catch (_) {
      // 半截文件删不掉，下次 clear 再删
    }
  }

  /// GET 安装包：连接 15 秒、拿到响应头 30 秒、之后 30 秒没收到数据都算失败，只认 200。
  static Future<({int? length, Stream<List<int>> body})> _httpOpen(Uri url) async {
    final client = HttpClient()..connectionTimeout = const Duration(seconds: 15);
    try {
      final response = await client.getUrl(url).then((request) => request.close()).timeout(const Duration(seconds: 30));
      if (response.statusCode != HttpStatus.ok) {
        unawaited(response.drain<void>().catchError((_) {}));
        throw HttpException('HTTP ${response.statusCode}', uri: url);
      }
      return (
        length: response.contentLength >= 0 ? response.contentLength : null,
        body: response.timeout(const Duration(seconds: 30)),
      );
    } finally {
      client.close(); // 不强制：正在下的连接下完再关
    }
  }
}

/// 首页更新提示用的下载安装器；测试换成假的。
var apkUpdater = ApkUpdater();
