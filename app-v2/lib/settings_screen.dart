import 'dart:io';

import 'package:flutter/material.dart';

import 'geolocation_service.dart';
import 'l10n/app_localizations.dart';
import 'main.dart';
import 'preferences.dart';
import 'qr_code_screen.dart';
import 'setup/checklist.dart';
import 'setup/checklist_screen.dart';
import 'setup/guard_section.dart';
import 'setup/password_tiles.dart';
import 'setup/recorder_section.dart';

/// 设置页（从根目录旧 App 搬来）：设备号、服务器；高级设置里是自检清单、防卸载、技术选项、测试用录制；App 密码一直看得到。
/// 原版和旧 App 的定位精度、距离、间隔、心跳、静止检测不再列：上报节奏由判断层定（R3），这些设置不起作用。
class SettingsScreen extends StatefulWidget {
  const SettingsScreen({super.key, this.checklistLoader = loadChecklist});

  /// 测试注入假数据。
  final Future<Checklist> Function() checklistLoader;

  @override
  State<SettingsScreen> createState() => _SettingsScreenState();
}

class _SettingsScreenState extends State<SettingsScreen> {
  bool advanced = false;
  Checklist? _checklist;

  @override
  void initState() {
    super.initState();
    _loadChecklist();
  }

  /// 读清单（「还差 N 项」）并刷新整页：密码设了、改了、关了也走这里。
  Future<void> _loadChecklist() async {
    final checklist = await widget.checklistLoader();
    if (mounted) setState(() => _checklist = checklist);
  }

  Future<void> _editText(String title, String key) async {
    final controller = TextEditingController(text: Preferences.instance.getString(key) ?? '');
    final errorMessage = AppLocalizations.of(context)!.invalidValue;
    final result = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        scrollable: true,
        title: Text(title),
        content: TextField(controller: controller),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: Text(AppLocalizations.of(context)!.cancelButton),
          ),
          TextButton(
            onPressed: () => Navigator.pop(context, controller.text.trim()),
            child: Text(AppLocalizations.of(context)!.saveButton),
          ),
        ],
      ),
    );
    if (result == null || result.isEmpty) return;
    if (key == Preferences.url) {
      final uri = Uri.tryParse(result);
      if (uri == null || uri.host.isEmpty || !(uri.scheme == 'http' || uri.scheme == 'https')) {
        messengerKey.currentState?.showSnackBar(SnackBar(content: Text(errorMessage)));
        return;
      }
    }
    await Preferences.instance.setString(key, result);
    await GeolocationService.tracker.setConfig(Preferences.buildConfig());
    setState(() {});
  }

  Widget _textTile(String title, String key) => ListTile(
        title: Text(title),
        subtitle: Text(Preferences.instance.getString(key) ?? ''),
        onTap: () => _editText(title, key),
      );

  Widget _checklistTile() {
    final l = AppLocalizations.of(context)!;
    final remaining = _checklist?.remaining;
    return ListTile(
      title: Text(l.checklistTitle),
      subtitle: remaining == null
          ? null
          : Text(
              remaining == 0 ? l.checklistAllDone : l.checklistRemaining(remaining),
              style: remaining == 0 ? null : TextStyle(color: Theme.of(context).colorScheme.error),
            ),
      trailing: const Icon(Icons.chevron_right),
      onTap: () async {
        await Navigator.push(context, MaterialPageRoute(builder: (_) => const ChecklistScreen()));
        _loadChecklist();
      },
    );
  }

  /// 技术开关：下面一行写后果。[warning] 不为空时，从默认值改掉前先弹框确认。
  Widget _techSwitch({
    required String title,
    required String hint,
    required String key,
    required bool defaultValue,
    String? warning,
  }) {
    final l = AppLocalizations.of(context)!;
    return SwitchListTile(
      title: Text(title),
      subtitle: Text(hint),
      value: Preferences.instance.getBool(key) ?? defaultValue,
      onChanged: (value) async {
        if (warning != null && value != defaultValue) {
          final confirmed = await showDialog<bool>(
            context: context,
            builder: (context) => AlertDialog(
              title: Text(l.techChangeTitle),
              content: Text(warning),
              actions: [
                TextButton(onPressed: () => Navigator.pop(context, false), child: Text(l.cancelButton)),
                TextButton(onPressed: () => Navigator.pop(context, true), child: Text(l.techChangeConfirmButton)),
              ],
            ),
          );
          if (confirmed != true) return;
        }
        await Preferences.instance.setBool(key, value);
        await GeolocationService.tracker.setConfig(Preferences.buildConfig());
        setState(() {});
      },
    );
  }

  /// 「仅系统定位」就是 SDK 的 preferPlatformProviders：不用高德。
  Widget _locationSourceTile() {
    final l = AppLocalizations.of(context)!;
    String label(bool systemOnly) => systemOnly ? l.locationSourceSystemLabel : l.locationSourceAutoLabel;
    final systemOnly = Preferences.instance.getBool(Preferences.preferPlatformProviders) ?? false;
    return ListTile(
      title: Text(l.locationSourceLabel),
      subtitle: Text('${label(systemOnly)}\n${l.locationSourceHint}'),
      onTap: () async {
        final selected = await showDialog<bool>(
          context: context,
          builder: (context) => SimpleDialog(
            title: Text(l.locationSourceLabel),
            children: [false, true]
                .map((option) => SimpleDialogOption(
                      child: Text(label(option)),
                      onPressed: () => Navigator.pop(context, option),
                    ))
                .toList(),
          ),
        );
        if (selected != null && selected != systemOnly) {
          await Preferences.instance.setBool(Preferences.preferPlatformProviders, selected);
          await GeolocationService.tracker.setConfig(Preferences.buildConfig());
          setState(() {});
        }
      },
    );
  }

  /// 离线缓冲、唤醒锁、定位来源：放在高级设置最下面，默认折叠。
  Widget _techOptions() {
    final l = AppLocalizations.of(context)!;
    return ExpansionTile(
      title: Text(l.techOptionsTitle),
      children: [
        _techSwitch(
          title: l.bufferLabel,
          hint: l.bufferHint,
          key: Preferences.buffer,
          defaultValue: true,
          warning: l.bufferOffWarning,
        ),
        if (Platform.isAndroid)
          _techSwitch(title: l.wakelockLabel, hint: l.wakelockHint, key: Preferences.wakelock, defaultValue: false),
        if (Platform.isAndroid) _locationSourceTile(),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    return Scaffold(
      appBar: AppBar(
        title: Text(l.settingsTitle),
        actions: [
          IconButton(
            icon: const Icon(Icons.qr_code_scanner),
            tooltip: l.scanConfigTooltip,
            onPressed: () async {
              await Navigator.push(context, MaterialPageRoute(builder: (_) => const QrCodeScreen()));
              setState(() {});
            },
          ),
        ],
      ),
      body: ListView(
        padding: EdgeInsets.only(bottom: MediaQuery.paddingOf(context).bottom),
        children: [
          _textTile(l.idLabel, Preferences.id),
          _textTile(l.urlLabel, Preferences.url),
          SwitchListTile(
            title: Text(l.advancedLabel),
            value: advanced,
            onChanged: (value) => setState(() => advanced = value),
          ),
          // 只做 Android（ios/ 不维护）：清单、防卸载、录制直接用原生通道
          if (advanced) _checklistTile(),
          if (advanced) GuardSection(onChanged: _loadChecklist),
          if (advanced) _techOptions(),
          if (advanced) const RecorderSection(),
          // App 密码不放进高级设置：没打开高级设置也看得到设置、修改、关闭
          PasswordTiles(onChanged: _loadChecklist),
        ],
      ),
    );
  }
}
