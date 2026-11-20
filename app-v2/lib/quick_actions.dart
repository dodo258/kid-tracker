import 'dart:developer' as developer;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:quick_actions/quick_actions.dart';

import 'geolocation_service.dart';
import 'l10n/app_localizations.dart';
import 'password_service.dart';
import 'sos.dart';

class QuickActionsInitializer extends StatefulWidget {
  const QuickActionsInitializer({super.key});

  @override
  State<QuickActionsInitializer> createState() => _QuickActionsInitializerState();
}

class _QuickActionsInitializerState extends State<QuickActionsInitializer> {
  final QuickActions quickActions = QuickActions();

  @override
  void initState() {
    super.initState();
    quickActions.initialize((shortcutType) async {
      developer.log('quick_action: $shortcutType');
      if (shortcutType == 'sos') {
        // 求救不用家长密码；结果留在求救界面（没发出去可以再发），发出去了才退回桌面（审计 M3）
        await sosController.send(exitWhenSent: true);
        return;
      }
      if (shortcutType == 'start' || shortcutType == 'stop') {
        await WidgetsBinding.instance.endOfFrame;
        if (!mounted) return;
        final l = AppLocalizations.of(context)!;
        final action = shortcutType == 'start' ? l.pendingActionStart : l.pendingActionStop;
        if (!await PasswordService.authenticate(context, action: action) || !mounted) return;
      }
      try {
        switch (shortcutType) {
          case 'start':
            await GeolocationService.tracker.start();
          case 'stop':
            await GeolocationService.tracker.stop();
        }
      } on PlatformException {
        // permission denied or startup error
      }
      if (mounted) {
        developer.log('quick_action_exit');
        SystemNavigator.pop();
      }
    });
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    final localizations = AppLocalizations.of(context)!;
    quickActions.setShortcutItems(<ShortcutItem>[
      ShortcutItem(type: 'start', localizedTitle: localizations.startAction, icon: 'play'),
      ShortcutItem(type: 'stop', localizedTitle: localizations.stopAction, icon: 'stop'),
      ShortcutItem(type: 'sos', localizedTitle: localizations.sosAction, icon: 'exclamation'),
    ]);
  }

  @override
  Widget build(BuildContext context) {
    return const SizedBox.shrink();
  }
}
