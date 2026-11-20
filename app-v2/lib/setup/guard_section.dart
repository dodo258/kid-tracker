import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import '../password_service.dart';
import 'guard.dart';
import 'password_dialog.dart';

/// 开启防卸载：没有 App 密码先设一个（关闭防卸载要用它），再弹系统的激活设备管理员。开没开成以回来后读到的状态为准。
Future<void> enableAdmin(BuildContext context) async {
  final l = AppLocalizations.of(context)!;
  if (!hasAppPassword()) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l.guardNeedPassword)));
    await showSetPasswordDialog(context);
    if (!hasAppPassword()) return;
  }
  if (!await Guard.enableAdmin() && context.mounted) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l.guardAdminNotActivated)));
  }
}

/// 设置页「高级设置」里的防卸载开关：开要先有 App 密码，关要输 App 密码。
class GuardSection extends StatefulWidget {
  const GuardSection({super.key, this.onChanged});

  /// 状态变了（清单的「还差 N 项」可能跟着变）。
  final VoidCallback? onChanged;

  @override
  State<GuardSection> createState() => _GuardSectionState();
}

class _GuardSectionState extends State<GuardSection> {
  GuardState? _state;
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    // 从系统页面回来时重新读状态
    _lifecycle = AppLifecycleListener(onResume: _refresh);
    _refresh();
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  Future<void> _refresh() async {
    final state = await Guard.state();
    if (!mounted) return;
    setState(() => _state = state);
    widget.onChanged?.call();
  }

  Future<void> _toggle(bool on) async {
    if (on) {
      await enableAdmin(context);
    } else {
      if (!await PasswordService.authenticate(context)) return;
      await Guard.disableAdmin();
      // 系统停用设备管理员是异步的
      await Future<void>.delayed(const Duration(milliseconds: 500));
    }
    await _refresh();
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    final state = _state;
    if (state == null) return const SizedBox.shrink();
    return SwitchListTile(
      title: Text(l.guardAdminTitle),
      subtitle: Text.rich(TextSpan(children: [
        TextSpan(text: l.guardAdminWhy),
        if (state.lost) TextSpan(text: '\n${l.guardLost}', style: TextStyle(color: Theme.of(context).colorScheme.error)),
      ])),
      value: state.admin,
      onChanged: _toggle,
    );
  }
}
