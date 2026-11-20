import 'package:flutter/material.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import 'app_lock.dart';
import 'l10n/app_localizations.dart';
import 'password_hash.dart';
import 'preferences.dart';
import 'setup/password_attempts.dart';
import 'setup/password_dialog.dart';

class PasswordService {
  static final FlutterSecureStorage _secureStorage = const FlutterSecureStorage();

  /// 启动时把明文密码换成哈希：原版更早的版本存在 secure storage，原版存在设置里。
  /// 根目录旧 App 存的已经是哈希（同一个设置项），覆盖安装后原样认。
  static Future<void> migrate() async {
    final oldPassword = await _secureStorage.read(key: Preferences.password);
    if (oldPassword != null) {
      await Preferences.instance.setString(Preferences.password, PasswordHash.create(oldPassword));
      await _secureStorage.delete(key: Preferences.password);
      return;
    }
    final stored = Preferences.instance.getString(Preferences.password);
    if (stored != null && stored.isNotEmpty && !PasswordHash.isHashed(stored)) {
      await Preferences.instance.setString(Preferences.password, PasswordHash.create(stored));
    }
  }

  /// 改设置、关追踪之类的操作前要 App 密码；没设密码直接过。
  ///
  /// 打开 App 的密码页还盖着时（链接、桌面快捷冷启动）：等家长在那里解锁，解锁后再让家长确认 [action] 这一个操作，
  /// 不把「为看 App 输了密码」当成同意（审计 M2）；没解锁就离开了，这个操作作废。
  static Future<bool> authenticate(BuildContext context, {String? action}) async {
    final storedPassword = Preferences.instance.getString(Preferences.password);
    if (storedPassword == null || storedPassword.isEmpty) return true;
    if (appLock.locked) {
      if (!await appLock.whenUnlocked() || !context.mounted) return false;
      return confirmPendingAction(context, action);
    }
    if (!context.mounted) return false;
    final result = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      useSafeArea: true,
      builder: (context) => VerifyPasswordSheet(attempts: passwordAttempts),
    );
    return result == true;
  }

  /// 解锁后确认刚才等着的那个操作：「要停止追踪吗？」
  static Future<bool> confirmPendingAction(BuildContext context, String? action) async {
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) {
        final l = AppLocalizations.of(context)!;
        return AlertDialog(
          title: Text(l.pendingActionTitle),
          content: Text(action ?? l.pendingActionGeneric),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: Text(l.cancelButton)),
            TextButton(onPressed: () => Navigator.pop(context, true), child: Text(l.okButton)),
          ],
        );
      },
    );
    return confirmed == true;
  }

  static Future<void> setPassword(String password) async {
    if (password.isNotEmpty) {
      await Preferences.instance.setString(Preferences.password, PasswordHash.create(password));
    } else {
      await Preferences.instance.remove(Preferences.password);
    }
    await passwordAttempts.reset();
  }
}
