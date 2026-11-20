import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:app_links/app_links.dart';
import 'app_lock.dart';
import 'password_service.dart';
import 'quick_actions.dart';

import 'configuration_service.dart';
import 'geolocation_service.dart';
import 'l10n/app_localizations.dart';
import 'main_screen.dart';
import 'managed_config_service.dart';
import 'preferences.dart';
import 'sos.dart';

final messengerKey = GlobalKey<ScaffoldMessengerState>();
final navigatorKey = GlobalKey<NavigatorState>();
final mainScreenKey = GlobalKey<MainScreenState>();

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await Preferences.init();
  await GeolocationService.tracker.init(Preferences.buildConfig());
  await PasswordService.migrate();
  // 设了 App 密码：冷启动先锁住，输对才显示（从根目录旧 App 搬来）
  appLock.start();
  await ManagedConfigService.init();
  runApp(const MainApp());
}

class MainApp extends StatefulWidget {
  const MainApp({super.key});

  @override
  State<MainApp> createState() => _MainAppState();
}

class _MainAppState extends State<MainApp> {
  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) async {
      await _initLinks();
    });
  }

  Future<void> _initLinks() async {
    AppLinks().uriLinkStream.listen(_handleUri);
  }

  Future<void> _handleUri(Uri uri) async {
    final context = navigatorKey.currentState?.overlay?.context;
    if (Preferences.instance.getString(Preferences.password) != null) {
      if (context == null || !context.mounted) return;
      final l = AppLocalizations.of(context)!;
      // 打开 App 的密码页还盖着时，解锁后再让家长确认是哪个操作（审计 M2）
      final action = switch ((uri.host, uri.pathSegments.firstOrNull)) {
        ('action', 'start') => l.pendingActionStart,
        ('action', 'stop') => l.pendingActionStop,
        _ => l.pendingActionConfig,
      };
      if (!await PasswordService.authenticate(context, action: action) || !context.mounted) return;
    }
    if (uri.host == 'action') {
      try {
        switch (uri.pathSegments.firstOrNull) {
          case 'start':
            await GeolocationService.tracker.start();
          case 'stop':
            await GeolocationService.tracker.stop();
        }
      } on PlatformException {
        // permission denied or startup error
      }
      return;
    }
    if (context == null || !context.mounted) return;
    final confirmed = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        content: Text(AppLocalizations.of(context)!.configurationMessage),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context, false),
            child: Text(AppLocalizations.of(context)!.cancelButton),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, true),
            child: Text(AppLocalizations.of(context)!.okButton),
          ),
        ],
      ),
    );
    if (confirmed == true) {
      await ConfigurationService.applyUri(uri);
      mainScreenKey.currentState?.refresh();
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      scaffoldMessengerKey: messengerKey,
      navigatorKey: navigatorKey,
      localizationsDelegates: AppLocalizations.localizationsDelegates,
      supportedLocales: AppLocalizations.supportedLocales,
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.green,
          brightness: Brightness.light,
        ),
      ),
      // 求救界面盖在所有页面上面（sos.dart），连打开 App 的密码页也盖住：桌面快捷求救不用家长密码
      builder: (context, child) =>
          SosLayer(controller: sosController, child: AppLockGate(lock: appLock, child: child!)),
      darkTheme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: Colors.green,
          brightness: Brightness.dark,
        ),
      ),
      home: Stack(
        children: [
          const QuickActionsInitializer(),
          MainScreen(key: mainScreenKey),
        ],
      ),
    );
  }
}
