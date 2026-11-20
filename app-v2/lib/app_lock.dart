import 'dart:async';

import 'package:flutter/material.dart';

import 'l10n/app_localizations.dart';
import 'password_hash.dart';
import 'preferences.dart';
import 'setup/passcode_field.dart';
import 'setup/password_attempts.dart';
import 'setup/password_dialog.dart' show isPin, verifyMessage, verifyPassword;

/// 打开 App 要密码：设了 App 密码后，冷启动和从后台回到前台都先锁住，输对才显示内容。
class AppLock extends ChangeNotifier {
  AppLock({required this.password, DateTime Function()? now}) : _now = now ?? DateTime.now;

  /// 存着的 App 密码（哈希，老版本是明文）；没设是 null 或空串。
  final String? Function() password;
  final DateTime Function() _now;

  /// App 自己打开系统设置页后，这么久内回来不锁：清单「去设置」要来回跳很多次。
  static const externalGrace = Duration(minutes: 10);

  /// 打开外部页面后这么久内退到后台，才算是这次跳转离开的。
  static const _leaveWindow = Duration(seconds: 10);

  bool _locked = false;
  DateTime? _externalAt;
  DateTime? _exemptHiddenAt;
  Completer<bool>? _unlocked;

  bool get locked => _locked;

  bool get _hasPassword => (password() ?? '').isNotEmpty;

  /// 冷启动时调用。
  void start() {
    if (_hasPassword) _lock();
  }

  /// App 马上要自己打开系统设置之类的外部页面：回来的这一次不锁。
  void expectExternalPage() => _externalAt = _now();

  /// 退到后台（界面看不见了）。锁着时等解锁的操作（链接、快捷方式）作废：家长离开了，不能等下次为看 App 解锁时顺带批准（审计 M2）。
  void onHidden() {
    if (_locked) _discardPending();
    final externalAt = _externalAt;
    _externalAt = null;
    if (externalAt != null && _now().difference(externalAt) < _leaveWindow) {
      _exemptHiddenAt = _now();
      return;
    }
    if (_hasPassword) _lock();
  }

  /// 回到前台。
  void onShown() {
    final hiddenAt = _exemptHiddenAt;
    _exemptHiddenAt = null;
    if (hiddenAt != null && _now().difference(hiddenAt) > externalGrace && _hasPassword) _lock();
  }

  /// 输对了解锁，返回是否输对。
  bool unlock(String input) {
    if (!_locked) return true;
    if (_hasPassword && !PasswordHash.matches(password(), input)) return false;
    _locked = false;
    _unlocked?.complete(true);
    _unlocked = null;
    notifyListeners();
    return true;
  }

  /// 锁着时等到解锁：解锁了返回 true；没解锁就退到后台返回 false（等着的操作作废）。
  /// 解锁只说明密码对了，不等于同意了等着的操作：调用方还要让家长确认这一个操作（PasswordService.authenticate）。
  Future<bool> whenUnlocked() => _locked ? (_unlocked ??= Completer<bool>()).future : Future<bool>.value(true);

  void _discardPending() {
    _unlocked?.complete(false);
    _unlocked = null;
  }

  void _lock() {
    if (_locked) return;
    _locked = true;
    notifyListeners();
  }
}

final appLock = AppLock(password: () => Preferences.instance.getString(Preferences.password));

/// 包在 MaterialApp.builder 里：锁着时盖住整个 App（包括弹窗），下面的页面保留状态但不显示、不接收输入。
class AppLockGate extends StatefulWidget {
  AppLockGate({super.key, required this.lock, PasswordAttempts? attempts, required this.child})
    : attempts = attempts ?? passwordAttempts;

  final AppLock lock;
  final PasswordAttempts attempts;
  final Widget child;

  @override
  State<AppLockGate> createState() => _AppLockGateState();
}

class _AppLockGateState extends State<AppLockGate> {
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    _lifecycle = AppLifecycleListener(onHide: widget.lock.onHidden, onShow: widget.lock.onShown);
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.lock,
      builder: (context, child) {
        final locked = widget.lock.locked;
        return Stack(
          children: [
            ExcludeFocus(
              excluding: locked,
              child: Offstage(offstage: locked, child: TickerMode(enabled: !locked, child: child!)),
            ),
            if (locked) LockScreen(lock: widget.lock, attempts: widget.attempts),
          ],
        );
      },
      child: widget.child,
    );
  }
}

class LockScreen extends StatefulWidget {
  const LockScreen({super.key, required this.lock, required this.attempts});

  final AppLock lock;
  final PasswordAttempts attempts;

  @override
  State<LockScreen> createState() => _LockScreenState();
}

class _LockScreenState extends State<LockScreen> {
  // 锁屏在 Navigator 外面，自己带 Overlay（老密码带字母时的输入框要用）
  late final _page = OverlayEntry(builder: _buildPage);

  @override
  Widget build(BuildContext context) => Overlay(initialEntries: [_page]);

  Widget _buildPage(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    final (message, isError) = verifyMessage(l, widget.attempts, l.lockHint);
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(24, 0, 24, 40),
              child: PasscodeField(
                expand: true,
                title: l.lockTitle,
                message: message,
                messageIsError: isError,
                digitsOnly: isPin(widget.lock.password()),
                onSubmit: (input) async {
                  final feedback = await verifyPassword(l, widget.attempts, input);
                  if (feedback == null) widget.lock.unlock(input);
                  return feedback;
                },
              ),
            ),
          ),
        ),
      ),
    );
  }
}
