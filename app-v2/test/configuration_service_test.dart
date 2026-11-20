// 用相对路径引 lib/（原因见 legacy_data_test.dart 开头）
// ignore_for_file: avoid_relative_lib_imports
import 'package:flutter_test/flutter_test.dart';
import '../lib/configuration_service.dart';

/// 扫码只认本 App 的配置码，普通网址直接忽略（扫错网页码不能把位置发到别的网站）。
void main() {
  Uri? parse(String raw) => ConfigurationService.parseConfigCode(raw);

  test('本 App 的配置码：服务器地址、设备号、其他参数', () {
    final uri = parse('com.dodo258.tracker://config?url=https%3A%2F%2Fgps.example.com%2Fosmand&id=12345678');
    expect(uri, isNotNull);
    expect(uri!.queryParameters['url'], 'https://gps.example.com/osmand');
    expect(uri.queryParameters['id'], '12345678');
    expect(parse('com.dodo258.tracker://config?interval=30'), isNotNull);
    expect(parse('  com.dodo258.tracker://config?id=1  '), isNotNull);
  });

  test('普通网址忽略，哪怕带 id 之类的参数', () {
    expect(parse('https://shop.example.com/'), isNull);
    expect(parse('https://shop.example.com/item?id=123'), isNull);
    expect(parse('http://gps.example.com:5055?id=123&interval=30'), isNull);
  });

  test('本 App 的链接但不是配置：没参数、只有无关参数、action 链接', () {
    expect(parse('com.dodo258.tracker://config'), isNull);
    expect(parse('com.dodo258.tracker://config?foo=bar'), isNull);
    expect(parse('com.dodo258.tracker://action/stop?id=1'), isNull);
  });

  test('服务器地址不是 http(s) 网址的不认', () {
    expect(parse('com.dodo258.tracker://config?url=javascript:alert(1)'), isNull);
    expect(parse('com.dodo258.tracker://config?url=ftp%3A%2F%2Fx.com'), isNull);
    expect(parse('com.dodo258.tracker://config?url=not%20a%20url'), isNull);
  });

  test('别的码（文本、别的 App 链接）忽略', () {
    expect(parse('hello'), isNull);
    expect(parse('org.traccar.client://config?id=1'), isNull);
    expect(parse(''), isNull);
  });
}
