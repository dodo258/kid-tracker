import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import 'password_dialog.dart';

/// 设置页的 App 密码入口：没设时一行「App 密码 · 未设置」点了去设置；设了是「修改密码」「关闭密码」两行，都要先输当前密码。
class PasswordTiles extends StatelessWidget {
  const PasswordTiles({super.key, required this.onChanged});

  /// 设了、改了或关了密码后调用，让设置页刷新这几行和清单状态。
  final VoidCallback onChanged;

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    Future<void> run(Future<bool> Function(BuildContext) action) async {
      if (await action(context)) onChanged();
    }

    if (!hasAppPassword()) {
      return ListTile(
        title: Text(l.passwordItem),
        subtitle: Text(l.passwordNotSet, style: TextStyle(color: Theme.of(context).colorScheme.error)),
        trailing: const Icon(Icons.chevron_right),
        onTap: () => run(showSetPasswordDialog),
      );
    }
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        ListTile(
          leading: const Icon(Icons.lock_reset),
          title: Text(l.changePasswordButton),
          onTap: () => run(showSetPasswordDialog),
        ),
        ListTile(
          leading: const Icon(Icons.lock_open),
          title: Text(l.disablePasswordButton),
          onTap: () => run(showDisablePasswordDialog),
        ),
      ],
    );
  }
}
