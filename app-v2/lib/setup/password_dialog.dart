import 'dart:math' as math;

import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import '../password_hash.dart';
import '../password_service.dart';
import '../preferences.dart';
import 'passcode_field.dart';
import 'password_attempts.dart';

/// 新设的密码只能是数字：配圆形数字键，像系统锁屏。
const minPinLength = 4;
const maxPinLength = 12;

/// 老版本可能设过带字母的密码：那种还用安全输入框加系统键盘来输。[stored] 是存着的密码（哈希或老的明文）。
bool isPin(String? stored) => PasswordHash.isPin(stored);

/// 启动密码页和改设置的密码弹窗共用：对了返回 null；错了「还能试 N 次」；第 5 次「即将退出」并停用键盘。
Future<EntryFeedback?> verifyPassword(AppLocalizations l, PasswordAttempts attempts, String input) async {
  switch (await attempts.check(input)) {
    case PasswordCheck.ok:
      return null;
    case PasswordCheck.wrong:
      return EntryFeedback(l.passwordWrongRemaining(attempts.remaining));
    case PasswordCheck.lockedOut:
      return EntryFeedback(l.passwordLockedOut(PasswordAttempts.maxFailures), disabled: true);
  }
}

/// 一进来的提示行：之前输错过（App 重启了也算）就先说还能试几次。
(String, bool) verifyMessage(AppLocalizations l, PasswordAttempts attempts, String normal) =>
    attempts.failures > 0 ? (l.passwordRemaining(attempts.remaining), true) : (normal, false);

/// 改设置、关追踪之类的操作前从底部弹出的密码框，样式同启动密码页；输对返回 true。
class VerifyPasswordSheet extends StatelessWidget {
  const VerifyPasswordSheet({super.key, required this.attempts});

  final PasswordAttempts attempts;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    final (message, isError) = verifyMessage(l, attempts, l.verifyHint);
    // 底部让开系统导航栏；老密码用输入框时让开键盘
    final bottom = math.max(MediaQuery.viewPaddingOf(context).bottom, MediaQuery.viewInsetsOf(context).bottom);
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 8, 16, 24 + bottom),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              const SizedBox(width: 48),
              Expanded(
                child: Text(l.lockTitle, textAlign: TextAlign.center, style: Theme.of(context).textTheme.titleLarge),
              ),
              CloseButton(onPressed: () => Navigator.pop(context, false)),
            ],
          ),
          const SizedBox(height: 4),
          PasscodeField(
            message: message,
            messageIsError: isError,
            digitsOnly: isPin(attempts.password()),
            keySize: 68,
            onSubmit: (input) async {
              final feedback = await verifyPassword(l, attempts, input);
              if (feedback == null && context.mounted) Navigator.pop(context, true);
              return feedback;
            },
          ),
        ],
      ),
    );
  }
}

/// 设置或修改 App 密码：整页。已有密码时先输当前密码，再输两次新密码；没有就直接输两次。存好了返回 true。
/// 设置页、清单、首页提示、一键防护共用。
Future<bool> showSetPasswordDialog(BuildContext context) => _pushPage(context, const SetPasswordPage());

/// 关闭 App 密码：整页，输对当前密码才关。关了返回 true。
Future<bool> showDisablePasswordDialog(BuildContext context) => _pushPage(context, const DisablePasswordPage());

Future<bool> _pushPage(BuildContext context, Widget page) async =>
    await Navigator.of(context).push<bool>(MaterialPageRoute(fullscreenDialog: true, builder: (context) => page)) ??
    false;

bool hasAppPassword() => (Preferences.instance.getString(Preferences.password) ?? '').isNotEmpty;

/// 关闭按钮加居中的数字键盘，设置/修改/关闭密码三个整页共用。
class _PasscodePage extends StatelessWidget {
  const _PasscodePage({required this.field});

  final PasscodeField field;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(leading: CloseButton(onPressed: () => Navigator.pop(context, false))),
      body: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Padding(padding: const EdgeInsets.fromLTRB(24, 0, 24, 32), child: field),
          ),
        ),
      ),
    );
  }
}

/// 先验当前密码：和启动密码页共用输错计数，在这里乱试也是 5 次就退出。
PasscodeField _currentPasswordField(
  BuildContext context, {
  Key? key,
  required String title,
  required String hint,
  required Future<void> Function() onVerified,
}) {
  final l = AppLocalizations.of(context)!;
  final (message, isError) = verifyMessage(l, passwordAttempts, hint);
  return PasscodeField(
    key: key,
    expand: true,
    title: title,
    message: message,
    messageIsError: isError,
    digitsOnly: isPin(passwordAttempts.password()),
    onSubmit: (input) async {
      final feedback = await verifyPassword(l, passwordAttempts, input);
      if (feedback == null) await onVerified();
      return feedback;
    },
  );
}

void _toast(BuildContext context, String message) =>
    ScaffoldMessenger.maybeOf(context)?.showSnackBar(SnackBar(content: Text(message)));

class SetPasswordPage extends StatefulWidget {
  const SetPasswordPage({super.key});

  @override
  State<SetPasswordPage> createState() => _SetPasswordPageState();
}

class _SetPasswordPageState extends State<SetPasswordPage> {
  /// 打开时就有密码：这是「修改密码」，要先过当前密码这一步。
  final bool _changing = hasAppPassword();
  late bool _verified = !_changing;
  String? _first;

  Future<EntryFeedback?> _submit(String input) async {
    final l = AppLocalizations.of(context)!;
    if (_first == null) {
      setState(() => _first = input);
      return EntryFeedback(l.confirmPasswordHint, error: false);
    }
    if (input != _first) {
      setState(() => _first = null);
      return EntryFeedback(l.passwordMismatch);
    }
    await PasswordService.setPassword(input);
    if (!mounted) return null;
    if (_changing) _toast(context, l.passwordChanged);
    Navigator.pop(context, true);
    return null;
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    if (!_verified) {
      return _PasscodePage(
        field: _currentPasswordField(
          context,
          key: const ValueKey('current'),
          title: l.currentPasswordTitle,
          hint: l.changePasswordHint,
          onVerified: () async => setState(() => _verified = true),
        ),
      );
    }
    return _PasscodePage(
      field: PasscodeField(
        key: const ValueKey('new'),
        expand: true,
        title: _first != null
            ? l.confirmPasswordTitle
            : _changing
                ? l.newPasswordTitle
                : l.setPasswordTitle,
        message: _first == null ? l.setPasswordHint(minPinLength, maxPinLength) : l.confirmPasswordHint,
        minLength: minPinLength,
        maxLength: maxPinLength,
        onSubmit: _submit,
      ),
    );
  }
}

class DisablePasswordPage extends StatelessWidget {
  const DisablePasswordPage({super.key});

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    return _PasscodePage(
      field: _currentPasswordField(
        context,
        title: l.disablePasswordTitle,
        hint: l.disablePasswordHint,
        onVerified: () async {
          await PasswordService.setPassword('');
          if (!context.mounted) return;
          _toast(context, l.passwordDisabled);
          Navigator.pop(context, true);
        },
      ),
    );
  }
}
