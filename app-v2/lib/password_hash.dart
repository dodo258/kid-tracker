import 'dart:convert';
import 'dart:math';
import 'dart:typed_data';

import 'package:crypto/crypto.dart';

/// App 密码只存加盐哈希（PBKDF2-HMAC-SHA256），不存明文：
/// `pbkdf2-sha256$<轮数>$<pin|text>$<盐 base64>$<哈希 base64>`。
/// pin/text 记下是不是纯数字，验证时选数字键盘还是系统键盘。
/// 老版本存的是明文：[matches] 照样认，启动时 PasswordService.migrate 换成哈希。
class PasswordHash {
  static const _scheme = 'pbkdf2-sha256';
  static const defaultIterations = 10000;
  static const _saltBytes = 16;

  static final _random = Random.secure();

  static String create(String password, {int iterations = defaultIterations, Random? random}) {
    final rng = random ?? _random;
    final salt = Uint8List.fromList(List.generate(_saltBytes, (_) => rng.nextInt(256)));
    final hash = pbkdf2(utf8.encode(password), salt, iterations);
    final kind = _isDigits(password) ? 'pin' : 'text';
    return [_scheme, iterations, kind, base64.encode(salt), base64.encode(hash)].join(r'$');
  }

  static bool isHashed(String? stored) => stored != null && stored.startsWith('$_scheme\$');

  /// [input] 是不是 [stored] 对应的密码。没设密码（null、空串）、格式坏了都返回 false。
  static bool matches(String? stored, String input) {
    if (stored == null || stored.isEmpty) return false;
    if (!isHashed(stored)) return _equal(utf8.encode(stored), utf8.encode(input));
    final parts = stored.split(r'$');
    if (parts.length != 5) return false;
    final iterations = int.tryParse(parts[1]);
    if (iterations == null || iterations < 1) return false;
    try {
      final salt = base64.decode(parts[3]);
      final expected = base64.decode(parts[4]);
      return _equal(pbkdf2(utf8.encode(input), salt, iterations, length: expected.length), expected);
    } on FormatException {
      return false;
    }
  }

  /// 存着的密码是不是纯数字（输入时配数字键盘）。
  static bool isPin(String? stored) {
    if (stored == null || stored.isEmpty) return false;
    if (isHashed(stored)) return stored.split(r'$').elementAtOrNull(2) == 'pin';
    return _isDigits(stored);
  }

  static bool _isDigits(String s) => RegExp(r'^\d+$').hasMatch(s);

  /// 长度相同时逐字节比完，不因提前退出泄露位置。
  static bool _equal(List<int> a, List<int> b) {
    if (a.length != b.length) return false;
    var diff = 0;
    for (var i = 0; i < a.length; i++) {
      diff |= a[i] ^ b[i];
    }
    return diff == 0;
  }

  /// RFC 8018 的 PBKDF2，PRF 用 HMAC-SHA256。
  static Uint8List pbkdf2(List<int> password, List<int> salt, int iterations, {int length = 32}) {
    final hmac = Hmac(sha256, password);
    final out = BytesBuilder(copy: false);
    for (var block = 1; out.length < length; block++) {
      var u = hmac.convert([...salt, block >> 24 & 0xff, block >> 16 & 0xff, block >> 8 & 0xff, block & 0xff]).bytes;
      final t = Uint8List.fromList(u);
      for (var i = 1; i < iterations; i++) {
        u = hmac.convert(u).bytes;
        for (var j = 0; j < t.length; j++) {
          t[j] ^= u[j];
        }
      }
      out.add(t);
    }
    return Uint8List.sublistView(out.takeBytes(), 0, length);
  }
}
