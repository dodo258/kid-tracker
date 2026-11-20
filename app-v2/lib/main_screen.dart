import 'dart:async';
import 'dart:developer' as developer;
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';

import 'geolocation_service.dart';
import 'l10n/app_localizations.dart';
import 'main.dart';
import 'password_service.dart';
import 'preferences.dart';
import 'settings_screen.dart';
import 'setup/background_check.dart';
import 'setup/checklist.dart';
import 'setup/checklist_screen.dart';
import 'setup/last_report.dart';
import 'setup/password_dialog.dart';
import 'sos.dart';
import 'status_screen.dart';
import 'update/apk_updater.dart';
import 'update/app_update.dart';
import 'update/update_banner.dart';

/// 首页（界面和交互沿用根目录旧 App）：最上面是新版本提示、清单提示；追踪卡片有设备号（可复制）、上次成功上报、开关、
/// 发送位置、求救、日志；设置卡片有服务器、设置、自检清单。
class MainScreen extends StatefulWidget {
  const MainScreen({super.key, this.checklistLoader = loadChecklist});

  /// 测试注入假数据。
  final Future<Checklist> Function() checklistLoader;

  @override
  State<MainScreen> createState() => MainScreenState();
}

class MainScreenState extends State<MainScreen> with WidgetsBindingObserver {
  static final _reportFormat = DateFormat('yyyy-MM-dd HH:mm');

  /// 判断层静止时 5 分钟呼吸一次：超过 3 次没报出去（15 分钟）首页标红。
  static const breathSeconds = 300;

  bool trackingEnabled = false;
  ReportStatus? _report;
  Timer? _reportTimer;
  HomePrompt _prompt = HomePrompt.none;
  int _remaining = 0;
  bool _checklistOpen = false;
  bool _sendingLocation = false;
  UpdateInfo? _update;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _refreshState();
    _reportTimer = Timer.periodic(const Duration(minutes: 1), (_) {
      if (WidgetsBinding.instance.lifecycleState == AppLifecycleState.resumed) _refreshState();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _reportTimer?.cancel();
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) _refreshState();
  }

  /// 启动、回到前台、每分钟刷新一次：追踪状态、上次成功上报、清单提示（系统项现读、厂商项看实际表现），再查一次更新。
  Future<void> _refreshState() async {
    final tracking = await GeolocationService.tracker.isTracking();
    final logs = await GeolocationService.tracker.getLogs();
    final checklist = await widget.checklistLoader();
    final savedFingerprint = await BackgroundCheck.savedFingerprint();
    if (!mounted) return;
    setState(() {
      trackingEnabled = tracking;
      _prompt = homePrompt(checklist, savedFingerprint: savedFingerprint);
      _remaining = checklist.remaining;
      _report = reportStatus(logs, tracking: tracking, heartbeatSeconds: breathSeconds, now: DateTime.now());
    });
    _checkUpdate();
  }

  /// 前台打开时查更新（一天联网一次，失败静默），有新版本就在首页最上面提示；没有就删掉下过的安装包。
  Future<void> _checkUpdate() async {
    final update = await appUpdate.available();
    if (update == null) await apkUpdater.clear();
    if (mounted) setState(() => _update = update);
  }

  void refresh() => setState(() {});

  Future<void> openChecklist() async {
    if (_checklistOpen) return;
    _checklistOpen = true;
    await Navigator.push(context, MaterialPageRoute(builder: (_) => const ChecklistScreen()));
    _checklistOpen = false;
    _refreshState();
  }

  /// 打开追踪前的门槛：系统项、防卸载、App 密码还有 ✗ 就提示还差几项、直接进清单。厂商项不卡（开着追踪才验证得了）。
  /// 只在「打开」时查；已经开着的追踪不因清单变化停止。
  Future<bool> checkCanStart() async {
    final blocking = (await widget.checklistLoader()).blocking;
    if (blocking == 0) return true;
    if (mounted) {
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text(AppLocalizations.of(context)!.trackingBlocked(blocking))),
      );
      openChecklist();
    }
    return false;
  }

  /// 发送位置：先显示「正在发送…」，有结果后换成结果提示。
  Future<void> _sendLocation() async {
    final l = AppLocalizations.of(context)!;
    final messenger = ScaffoldMessenger.of(context);
    setState(() => _sendingLocation = true);
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(l.sendingMessage), duration: const Duration(minutes: 2)));
    String message;
    try {
      final sent = await GeolocationService.tracker.requestPosition();
      message = sent ? l.locationSentMessage : l.locationFailedMessage;
    } on PlatformException catch (e) {
      // 没有定位权限时原生侧抛 IllegalStateException("Location permission denied")
      message = (e.message?.contains('permission') ?? false)
          ? l.sendNoPermissionMessage
          : l.sendErrorMessage(e.message ?? e.code);
    }
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message), duration: const Duration(seconds: 5)));
    if (mounted) setState(() => _sendingLocation = false);
    _refreshState();
  }

  Future<void> _setPassword() async {
    if (await showSetPasswordDialog(context)) _refreshState();
  }

  Future<void> _toggleTracking(bool value) async {
    if (value && !await checkCanStart()) return;
    if (!mounted || !await PasswordService.authenticate(context) || !mounted) return;
    if (!value) {
      developer.log('tracking_toggle_stop');
      // SDK 关之前先记一条 trackingOff 告警（存好就关，后台去发）
      await GeolocationService.tracker.stop();
      if (mounted) setState(() => trackingEnabled = false);
      return;
    }
    developer.log('tracking_toggle_start');
    var started = false;
    try {
      await GeolocationService.tracker.start();
      started = true;
    } on PlatformException {
      // permission denied or startup error
    }
    if (!mounted) return;
    if (!started) {
      messengerKey.currentState?.showSnackBar(
        SnackBar(content: Text(AppLocalizations.of(context)!.startFailedMessage), duration: const Duration(seconds: 4)),
      );
    }
    setState(() => trackingEnabled = started);
  }

  /// 首次打开、系统升级后、清单里有 ✗ 时，在首页最上面提示，一键进清单；只差没设 App 密码时是一行横条，直接设。
  Widget _buildChecklistPrompt() {
    final l = AppLocalizations.of(context)!;
    final colors = Theme.of(context).colorScheme;
    if (_prompt == HomePrompt.noPassword) {
      return Card(
        color: colors.errorContainer,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(16, 4, 8, 4),
          child: Row(
            children: [
              Expanded(child: Text(l.promptNoPassword, style: TextStyle(color: colors.onErrorContainer))),
              TextButton(onPressed: _setPassword, child: Text(l.promptSetPasswordButton)),
            ],
          ),
        ),
      );
    }
    final firstRun = _prompt == HomePrompt.firstRun;
    final message = switch (_prompt) {
      HomePrompt.firstRun => l.promptFirstRun,
      HomePrompt.systemUpdated => l.promptSystemUpdated,
      _ => l.promptIncomplete(_remaining),
    };
    return Card(
      color: firstRun ? colors.secondaryContainer : colors.errorContainer,
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(message, style: TextStyle(color: firstRun ? colors.onSecondaryContainer : colors.onErrorContainer)),
            const SizedBox(height: 12),
            FilledButton(onPressed: openChecklist, child: Text(l.promptOpenButton)),
          ],
        ),
      ),
    );
  }

  Widget _buildLastReport() {
    final l = AppLocalizations.of(context)!;
    final last = _report?.lastSuccess;
    final overdue = _report?.overdue ?? false;
    final style = overdue ? TextStyle(color: Theme.of(context).colorScheme.error) : null;
    return ListTile(
      contentPadding: EdgeInsets.zero,
      title: Text(l.lastReportLabel, style: style),
      subtitle: Text(
        [last == null ? l.lastReportNever : _reportFormat.format(last), if (overdue) l.reportOverdueMessage].join('\n'),
        style: style,
      ),
      onTap: overdue ? openChecklist : null,
    );
  }

  void _copyId() {
    Clipboard.setData(ClipboardData(text: Preferences.instance.getString(Preferences.id) ?? ''));
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(AppLocalizations.of(context)!.idCopiedMessage)));
  }

  Widget _buildTrackingCard() {
    final l = AppLocalizations.of(context)!;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(l.trackingTitle),
              titleTextStyle: Theme.of(context).textTheme.headlineMedium,
            ),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(l.idLabel),
              subtitle: Text(Preferences.instance.getString(Preferences.id) ?? ''),
              trailing: TextButton(onPressed: _copyId, child: Text(l.copyButton)),
            ),
            _buildLastReport(),
            if (Platform.isAndroid) ...[
              Text(l.disclosureMessage, style: Theme.of(context).textTheme.bodySmall),
              const SizedBox(height: 8),
            ],
            SwitchListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(l.trackingLabel),
              value: trackingEnabled,
              onChanged: _toggleTracking,
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilledButton.tonal(
                  onPressed: _sendingLocation ? null : _sendLocation,
                  child: Text(l.locationButton),
                ),
                FilledButton.tonal(
                  style: FilledButton.styleFrom(
                    backgroundColor: Theme.of(context).colorScheme.errorContainer,
                    foregroundColor: Theme.of(context).colorScheme.onErrorContainer,
                  ),
                  onPressed: () => confirmAndSendSos(context),
                  child: Text(l.sosAction),
                ),
                FilledButton.tonal(
                  onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const StatusScreen())),
                  child: Text(l.statusButton),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildSettingsCard() {
    final l = AppLocalizations.of(context)!;
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(l.settingsTitle),
              titleTextStyle: Theme.of(context).textTheme.headlineMedium,
            ),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Text(l.urlLabel),
              subtitle: Text(Preferences.instance.getString(Preferences.url) ?? ''),
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                FilledButton.tonal(
                  onPressed: () async {
                    if (await PasswordService.authenticate(context) && mounted) {
                      await Navigator.push(context, MaterialPageRoute(builder: (_) => const SettingsScreen()));
                      _refreshState();
                    }
                  },
                  child: Text(l.settingsButton),
                ),
                FilledButton.tonal(onPressed: openChecklist, child: Text(l.checklistButton)),
              ],
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: Text(AppLocalizations.of(context)!.appTitle)),
      body: SingleChildScrollView(
        padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + MediaQuery.paddingOf(context).bottom),
        child: Column(
          // 提示卡和追踪卡一样宽
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            if (_update != null) ...[
              UpdateBanner(info: _update!),
              const SizedBox(height: 16),
            ],
            if (_prompt != HomePrompt.none) ...[
              _buildChecklistPrompt(),
              const SizedBox(height: 16),
            ],
            _buildTrackingCard(),
            const SizedBox(height: 16),
            _buildSettingsCard(),
          ],
        ),
      ),
    );
  }
}
