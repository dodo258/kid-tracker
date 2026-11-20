import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:intl/intl.dart';
import 'package:traccar_client_sdk/traccar_client_sdk.dart';

import '../l10n/app_localizations.dart';
import 'background_check.dart';
import 'checklist.dart';
import 'checklist_texts.dart';
import 'guard_section.dart';
import 'password_dialog.dart';

/// 自检清单：全部自动判定，家长不用手动打勾。
/// - 系统能读的项每次回到 App 现读，开了自动打勾，没开给「去开启」直达设置页；
/// - 厂商项按实际表现判：待验证 → 已生效 / 没生效（给「去设置」或菜单路径，改过就重新验证）；
/// - 防卸载、App 密码在「防护」一组。
/// 只有 ✗ 的项展开说明和按钮，✓ 的只留一行；本机不需要的项收进最下面默认折叠的分组。
class ChecklistScreen extends StatefulWidget {
  const ChecklistScreen({super.key, this.load = loadChecklist});

  /// 测试注入假数据。
  final Future<Checklist> Function() load;

  @override
  State<ChecklistScreen> createState() => _ChecklistScreenState();
}

class _ChecklistScreenState extends State<ChecklistScreen> {
  Checklist? _checklist;

  /// 「去开启」「去设置」没打开专门页面、退回应用详情页的项，在这一行显示菜单路径。
  final _fellBack = <Object>{};
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    // 从系统设置页回来时重新读
    _lifecycle = AppLifecycleListener(onResume: _refresh);
    _refresh(saveFingerprint: true);
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    super.dispose();
  }

  /// 打开过清单就记下当前系统指纹，首页「首次使用 / 系统更新过」的提示随之消失。
  Future<void> _refresh({bool saveFingerprint = false}) async {
    final checklist = await widget.load();
    if (saveFingerprint) await BackgroundCheck.saveFingerprint(checklist.status.fingerprint);
    if (mounted) setState(() => _checklist = checklist);
  }

  Future<void> _open(Object key, Future<bool> Function() open) async {
    final opened = await open();
    if (mounted) setState(() => opened ? _fellBack.remove(key) : _fellBack.add(key));
  }

  /// 没生效的厂商项：家长去改设置，这项重新验证（回到「待验证」）。
  Future<void> _fixVendor(VendorEntry entry) async {
    await BackgroundCheck.resetHealth(entry.item.evidence);
    if (!entry.item.pathOnly) {
      await _open(entry.item, () => BackgroundCheck.openVendorSettings(entry.item));
    }
    await _refresh();
  }

  Future<void> _changePassword(Future<bool> Function(BuildContext) action) async {
    if (await action(context)) await _refresh();
  }

  void _copyQuestion(String device, String item) {
    final l = AppLocalizations.of(context)!;
    Clipboard.setData(ClipboardData(text: l.askAiPrompt(device, item)));
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(l.copiedMessage)));
  }

  Widget _mark(EntryState state) {
    final colors = Theme.of(context).colorScheme;
    final (text, color) = switch (state) {
      EntryState.done => ('✓', colors.primary),
      EntryState.todo => ('✗', colors.error),
      EntryState.pending => ('○', colors.outline),
      EntryState.notApplicable => ('–', colors.outline),
    };
    return SizedBox(
      width: 40,
      child: Center(child: Text(text, style: Theme.of(context).textTheme.titleLarge?.copyWith(color: color))),
    );
  }

  Widget _row({
    required EntryState state,
    required String title,
    required String device,
    List<InlineSpan> lines = const [],
    String? button,
    VoidCallback? onButton,
  }) {
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: _mark(state),
      title: Text(title),
      subtitle: lines.isEmpty ? null : Text.rich(TextSpan(children: lines)),
      trailing: button != null && onButton != null ? TextButton(onPressed: onButton, child: Text(button)) : null,
      onLongPress: () => _copyQuestion(device, title),
    );
  }

  TextSpan _error(String text) => TextSpan(text: text, style: TextStyle(color: Theme.of(context).colorScheme.error));

  Widget _autoRow(AutoEntry entry, BackgroundStatus status) {
    final l = AppLocalizations.of(context)!;
    final texts = autoTexts(l, entry.item, status.brand);
    return _row(
      state: entry.state,
      title: texts.title,
      device: status.deviceLabel,
      lines: switch (entry.state) {
        EntryState.todo => [
            _error(texts.why),
            if (_fellBack.contains(entry.item)) TextSpan(text: '\n${l.fallbackPath(texts.path)}'),
          ],
        EntryState.notApplicable =>
          [TextSpan(text: entry.item == CheckItem.exactAlarm ? l.checkExactAlarmNa : l.notApplicableMessage)],
        _ => const [],
      },
      // 要「关掉」的项（暂停闲置应用、省电模式、省流量）写「去设置」，别的写「去开启」
      button: entry.state != EntryState.todo
          ? null
          : const {CheckItem.hibernation, CheckItem.powerSave, CheckItem.dataSaver}.contains(entry.item)
              ? l.goToSettingsButton
              : l.goToEnableButton,
      onButton: () => _open(entry.item, () => BackgroundCheck.openSettings(entry.item)),
    );
  }

  /// 厂商项：待验证说怎么验证；已生效说依据；没生效说哪里不对、为什么要开、去哪设置。
  Widget _vendorRow(VendorEntry entry, BackgroundStatus status) {
    final l = AppLocalizations.of(context)!;
    final texts = vendorTexts(l, entry.item, status.brand);
    final evidence = entry.item.evidence;
    final lines = switch (entry.state) {
      EntryState.pending => [
          TextSpan(text: l.vendorPending, style: const TextStyle(fontWeight: FontWeight.w600)),
          TextSpan(text: switch (evidence) {
            HealthEvidence.reboot => l.vendorPendingReboot,
            HealthEvidence.breath => l.vendorPendingBreath,
            HealthEvidence.send => l.vendorPendingSend,
          }),
        ],
      EntryState.done => [
          TextSpan(text: switch (evidence) {
            HealthEvidence.reboot => l.vendorOkReboot,
            HealthEvidence.breath => l.vendorOkBreath,
            HealthEvidence.send => l.vendorOkSend,
          }),
        ],
      _ => [
          _error(switch (evidence) {
            HealthEvidence.reboot => l.vendorBadReboot,
            HealthEvidence.breath => (entry.judgement.detail ?? 0) > 0
                ? l.vendorBadBreathMinutes(entry.judgement.detail!)
                : l.vendorBadBreath,
            HealthEvidence.send => l.vendorBadSend,
          }),
          TextSpan(text: '\n${texts.why}'),
          if (entry.item.pathOnly || _fellBack.contains(entry.item))
            TextSpan(text: '\n${entry.item.pathOnly ? texts.path : l.fallbackPath(texts.path)}'),
        ],
    };
    return _row(
      state: entry.state,
      title: texts.title,
      device: status.deviceLabel,
      lines: lines,
      button: entry.state == EntryState.todo ? (entry.item.pathOnly ? l.vendorRecheckButton : l.goToSettingsButton) : null,
      onButton: () => _fixVendor(entry),
    );
  }

  Widget _adminRow(Checklist checklist) {
    final l = AppLocalizations.of(context)!;
    return _row(
      state: checklist.admin,
      title: l.guardAdminTitle,
      device: checklist.status.deviceLabel,
      lines: checklist.admin == EntryState.todo
          ? [_error(l.guardAdminWhy), if (checklist.adminLost) TextSpan(text: '\n${l.guardLost}')]
          : const [],
      button: checklist.admin == EntryState.todo ? l.goToEnableButton : null,
      onButton: () async {
        await enableAdmin(context);
        await _refresh();
      },
    );
  }

  Widget _passwordRow(Checklist checklist) {
    final l = AppLocalizations.of(context)!;
    if (checklist.password == EntryState.todo) {
      return _row(
        state: EntryState.todo,
        title: l.passwordItem,
        device: checklist.status.deviceLabel,
        lines: [_error(l.passwordWhy)],
        button: l.setPasswordButton,
        onButton: () => _changePassword(showSetPasswordDialog),
      );
    }
    // 设好了：一行，右边直接给「修改」「关闭」，都要先输当前密码
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: _mark(EntryState.done),
      title: Text(l.passwordItem),
      trailing: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          TextButton(onPressed: () => _changePassword(showSetPasswordDialog), child: Text(l.changePasswordButton)),
          TextButton(
            onPressed: () => _changePassword(showDisablePasswordDialog),
            child: Text(l.disablePasswordButton),
          ),
        ],
      ),
      onLongPress: () => _copyQuestion(checklist.status.deviceLabel, l.passwordItem),
    );
  }

  /// 最近一次异常（可靠性要求第 8 条）：哪一类用中文说，后面附英文原文给排查用；长按复制问 AI。
  Widget _faultRow(Checklist checklist) {
    final l = AppLocalizations.of(context)!;
    final theme = Theme.of(context);
    final fault = checklist.lastFault;
    final muted = theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant);
    return ListTile(
      contentPadding: EdgeInsets.zero,
      leading: _mark(fault == null ? EntryState.done : EntryState.pending),
      title: Text(fault == null ? l.faultNone : faultText(l, fault.kind)),
      subtitle: fault == null
          ? null
          : Text.rich(TextSpan(children: [
              TextSpan(text: _faultFormat.format(fault.time)),
              TextSpan(text: '\n${fault.message}', style: muted),
            ])),
      onLongPress: () => _copyQuestion(checklist.status.deviceLabel, l.faultSection),
    );
  }

  static final _faultFormat = DateFormat('yyyy-MM-dd HH:mm');

  Widget _notNeededSection(Checklist checklist) {
    final l = AppLocalizations.of(context)!;
    final entries = checklist.notApplicable;
    return Card(
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        shape: const Border(),
        collapsedShape: const Border(),
        title: Text(l.checklistNotNeededSection(entries.length), style: Theme.of(context).textTheme.titleMedium),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 8),
        children: [for (final entry in entries) _autoRow(entry, checklist.status)],
      ),
    );
  }

  Widget _section(String title, List<Widget> children, {String? note}) {
    final theme = Theme.of(context);
    return Card(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 16, 16, 8),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: theme.textTheme.titleMedium),
            if (note != null) ...[
              const SizedBox(height: 4),
              Text(note, style: theme.textTheme.bodySmall?.copyWith(color: theme.colorScheme.onSurfaceVariant)),
            ],
            ...children,
          ],
        ),
      ),
    );
  }

  Widget _summary(Checklist checklist) {
    final l = AppLocalizations.of(context)!;
    final theme = Theme.of(context);
    final remaining = checklist.remaining;
    final pending = checklist.vendor.where((e) => e.state == EntryState.pending).length;
    return Padding(
      padding: const EdgeInsets.fromLTRB(4, 0, 4, 8),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            remaining > 0
                ? l.checklistRemaining(remaining)
                : pending > 0
                    ? l.checklistPending(pending)
                    : l.checklistAllDone,
            style: theme.textTheme.headlineMedium?.copyWith(
              color: remaining > 0 ? theme.colorScheme.error : theme.colorScheme.primary,
            ),
          ),
          const SizedBox(height: 4),
          Text(l.checklistDeviceLabel(checklist.status.deviceLabel), style: theme.textTheme.bodySmall),
          const SizedBox(height: 8),
          Text(l.checklistIntroAuto),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    final checklist = _checklist;
    return Scaffold(
      appBar: AppBar(title: Text(l.checklistTitle)),
      body: checklist == null
          ? const Center(child: CircularProgressIndicator())
          : ListView(
              // 自己给了 padding 时 ListView 不再自动让开底部导航栏
              padding: EdgeInsets.fromLTRB(16, 16, 16, 16 + MediaQuery.paddingOf(context).bottom),
              children: [
                _summary(checklist),
                _section(l.checklistAutoSection, [
                  for (final entry in checklist.applicableAuto) _autoRow(entry, checklist.status),
                ]),
                const SizedBox(height: 8),
                if (checklist.vendor.isNotEmpty) ...[
                  _section(
                    l.checklistVendorSection,
                    [for (final entry in checklist.vendor) _vendorRow(entry, checklist.status)],
                    note: l.checklistVendorNote,
                  ),
                  const SizedBox(height: 8),
                ],
                if (checklist.applicableSuggestions.isNotEmpty) ...[
                  _section(l.checklistSuggestSection, [
                    for (final entry in checklist.applicableSuggestions) _autoRow(entry, checklist.status),
                  ]),
                  const SizedBox(height: 8),
                ],
                _section(l.guardSection, [_passwordRow(checklist), _adminRow(checklist)]),
                const SizedBox(height: 8),
                _section(l.faultSection, [_faultRow(checklist)], note: l.faultNote),
                if (checklist.notApplicable.isNotEmpty) ...[
                  const SizedBox(height: 8),
                  _notNeededSection(checklist),
                ],
              ],
            ),
    );
  }
}
