import 'geolocation_service.dart';
import 'preferences.dart';

class ConfigurationService {
  /// 本 App 的链接 scheme（深链和配置码共用）。
  static const scheme = 'com.dodo258.tracker';

  static const _configKeys = {
    Preferences.url, Preferences.id, Preferences.accuracy, Preferences.distance, Preferences.interval,
    Preferences.angle, Preferences.heartbeat, Preferences.buffer, Preferences.wakelock, Preferences.stopDetection,
    Preferences.preferPlatformProviders,
  };

  /// 扫码只认本 App 的配置码：`com.dodo258.tracker://config?url=https://服务器/osmand&id=设备号`（参数同深链配置），
  /// 至少带一个配置参数，url 有的话必须是 http(s) 地址。普通网址、控制追踪的 action 链接、别的码都返回 null，
  /// 不然扫错一个网页码，之后的位置就发到那个网站去了。
  static Uri? parseConfigCode(String raw) {
    final uri = Uri.tryParse(raw.trim());
    if (uri == null || uri.scheme != scheme || uri.host == 'action') return null;
    final params = uri.queryParameters;
    if (!params.keys.any(_configKeys.contains)) return null;
    final url = params[Preferences.url];
    if (url != null) {
      final server = Uri.tryParse(url);
      if (server == null || (server.scheme != 'https' && server.scheme != 'http') || server.host.isEmpty) return null;
    }
    return uri;
  }

  static Future<void> applyUri(Uri uri) async {
    final url = (uri.scheme == 'http' || uri.scheme == 'https')
        ? '${uri.origin}${uri.path}'
        : uri.queryParameters['url'];
    await applyParameters(uri.queryParameters, url: url);
  }

  static Future<void> applyManagedConfig(Map<String, dynamic> config) async {
    await applyParameters(config, url: config[Preferences.url]?.toString());
  }

  static Future<void> applyParameters(Map<String, dynamic> parameters, {String? url}) async {
    if (url != null) {
      await Preferences.instance.setString(Preferences.url, url);
    }
    await _applyStringParameter(parameters, Preferences.id);
    await _applyStringParameter(parameters, Preferences.accuracy);
    await _applyIntParameter(parameters, Preferences.distance);
    await _applyIntParameter(parameters, Preferences.interval);
    await _applyIntParameter(parameters, Preferences.angle);
    await _applyIntParameter(parameters, Preferences.heartbeat);
    await _applyBoolParameter(parameters, Preferences.buffer);
    await _applyBoolParameter(parameters, Preferences.wakelock);
    await _applyBoolParameter(parameters, Preferences.stopDetection);
    await _applyBoolParameter(parameters, Preferences.preferPlatformProviders);
    await GeolocationService.tracker.setConfig(Preferences.buildConfig());
  }

  static Future<void> _applyStringParameter(
      Map<String, dynamic> parameters, String key) async {
    final value = parameters[key];
    if (value != null) {
      await Preferences.instance.setString(key, value.toString());
    }
  }

  static Future<void> _applyIntParameter(
      Map<String, dynamic> parameters, String key) async {
    final raw = parameters[key];
    final value = raw is int ? raw : int.tryParse(raw?.toString() ?? '');
    if (value != null) {
      await Preferences.instance.setInt(key, value);
    }
  }

  static Future<void> _applyBoolParameter(
      Map<String, dynamic> parameters, String key) async {
    final raw = parameters[key];
    if (raw is bool) {
      await Preferences.instance.setBool(key, raw);
    } else if (raw is String) {
      switch (raw) {
        case 'false':
          await Preferences.instance.setBool(key, false);
        case 'true':
          await Preferences.instance.setBool(key, true);
      }
    }
  }
}
