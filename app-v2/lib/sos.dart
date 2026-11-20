import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'geolocation_service.dart';
import 'l10n/app_localizations.dart';

/// 求救的结果：服务器收到了；没收到（存在手机上，联网后 SDK 自动补发）；出错（比如还没有配置）。
enum SosOutcome { sent, queued, error }

class SosResult {
  const SosResult(this.outcome, {this.detail, this.id});

  final SosOutcome outcome;

  /// [SosOutcome.error] 时原生侧报的原因。
  final String? detail;

  /// 这条求救在手机上的编号（出错时没有）。
  final int? id;
}

/// 发一次求救：SDK 先存本机再发；还有没发出去的求救时是重发那一条，不另起一条。
/// [retry] 是上次那条的编号：只重发那一条，后台已经补发出去了就直接是 [SosOutcome.sent]。
Future<SosResult> sendSos(int? retry) async {
  try {
    final send = await GeolocationService.tracker.sendSos(retry: retry);
    return SosResult(send.sent ? SosOutcome.sent : SosOutcome.queued, id: send.id);
  } on PlatformException catch (e) {
    return SosResult(SosOutcome.error, detail: e.message ?? e.code);
  }
}

/// 这条求救后台补发出去了没有；问不到就当还没有。
Future<bool> isSosSent(int id) async {
  try {
    return await GeolocationService.tracker.isAlertSent(id);
  } on PlatformException {
    return false;
  }
}

/// 结果给孩子看的那句话。
String sosResultMessage(AppLocalizations l, SosResult result) => switch (result.outcome) {
      SosOutcome.sent => l.sosSentMessage,
      SosOutcome.queued => l.sosQueuedMessage,
      SosOutcome.error => l.sendErrorMessage(result.detail ?? ''),
    };

/// 求救界面的状态：首页按钮和桌面快捷共用。发送中、发出去、没发出去都让孩子看到（审计 M3）。
/// 界面开着时记住这条求救的编号：「再发一次」只重发它；没发出去时每 [pollEvery] 问一次，后台联网补发出去了就改成「已发给家长」，
/// 免得孩子看着「还没发出去」再按一次、家长收到两条。
class SosController extends ChangeNotifier {
  SosController({
    Future<SosResult> Function(int? retry)? send,
    Future<bool> Function(int id)? isSent,
    this.pollEvery = const Duration(seconds: 5),
  })  : _send = send ?? sendSos,
        _isSent = isSent ?? isSosSent;

  final Future<SosResult> Function(int? retry) _send;
  final Future<bool> Function(int id) _isSent;
  final Duration pollEvery;
  Timer? _poll;
  int? _id;
  bool _active = false;
  bool _sending = false;
  bool _exitWhenSent = false;
  SosResult? _result;

  /// 求救界面开着。
  bool get active => _active;
  bool get sending => _sending;

  /// 发出去后退回桌面（桌面快捷进来的）。
  bool get exitWhenSent => _exitWhenSent;

  /// 最近一次发送的结果；发送中是 null。
  SosResult? get result => _result;

  /// 发求救（再按就是重发）；[exitWhenSent] 只在打开界面的那一次定。
  Future<void> send({bool? exitWhenSent}) async {
    if (_sending) return;
    if (!_active) _exitWhenSent = exitWhenSent ?? false;
    _active = true;
    _sending = true;
    _result = null;
    notifyListeners();
    SosResult result;
    try {
      result = await _send(_id);
    } on Exception catch (e) {
      result = SosResult(SosOutcome.error, detail: '$e');
    }
    _id = result.id ?? _id;
    _sending = false;
    _result = result;
    notifyListeners();
    _watch();
  }

  void close() {
    if (_sending) return;
    _stopWatching();
    _active = false;
    _result = null;
    _id = null;
    notifyListeners();
  }

  /// 没发出去时盯着这条：后台补发出去了就改口
  void _watch() {
    _stopWatching();
    final id = _id;
    if (!_active || id == null || _result?.outcome != SosOutcome.queued) return;
    _poll = Timer.periodic(pollEvery, (_) async {
      if (_sending || _result?.outcome != SosOutcome.queued || _id != id) return _stopWatching();
      if (!await _isSent(id)) return;
      if (_sending || _id != id || _result?.outcome != SosOutcome.queued) return;
      _stopWatching();
      _result = SosResult(SosOutcome.sent, id: id);
      notifyListeners();
    });
  }

  void _stopWatching() {
    _poll?.cancel();
    _poll = null;
  }

  @override
  void dispose() {
    _stopWatching();
    super.dispose();
  }
}

final sosController = SosController();

/// 首页的求救按钮先确认一次，免得误碰。
Future<void> confirmAndSendSos(BuildContext context) async {
  final l = AppLocalizations.of(context)!;
  final confirmed = await showDialog<bool>(
    context: context,
    builder: (context) => AlertDialog(
      title: Text(l.sosConfirmTitle),
      content: Text(l.sosConfirmMessage),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context, false), child: Text(l.cancelButton)),
        FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(l.sosConfirmButton)),
      ],
    ),
  );
  if (confirmed == true) await sosController.send();
}

/// 包在 MaterialApp.builder 的最外层：求救界面盖在所有页面上面，桌面快捷求救不用输家长密码。
/// 发出去了显示「求救已发给家长」，[exitDelay] 后关掉（桌面快捷进来的就退回桌面）；
/// 没发出去留在这里，写清联网后会自动补发，可以再发一次或关掉。
class SosLayer extends StatefulWidget {
  const SosLayer({
    super.key,
    required this.controller,
    required this.child,
    this.exitDelay = const Duration(seconds: 3),
    this.onExit = SystemNavigator.pop,
  });

  final SosController controller;
  final Widget child;
  final Duration exitDelay;
  final Future<void> Function() onExit;

  @override
  State<SosLayer> createState() => _SosLayerState();
}

class _SosLayerState extends State<SosLayer> {
  Timer? _exitTimer;

  @override
  void initState() {
    super.initState();
    widget.controller.addListener(_onChange);
  }

  @override
  void didUpdateWidget(SosLayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    if (oldWidget.controller == widget.controller) return;
    oldWidget.controller.removeListener(_onChange);
    widget.controller.addListener(_onChange);
  }

  @override
  void dispose() {
    widget.controller.removeListener(_onChange);
    _exitTimer?.cancel();
    super.dispose();
  }

  void _onChange() {
    final controller = widget.controller;
    if (controller.result?.outcome != SosOutcome.sent) {
      _exitTimer?.cancel();
      _exitTimer = null;
    } else {
      _exitTimer ??= Timer(widget.exitDelay, () {
        _exitTimer = null;
        final exit = controller.exitWhenSent;
        controller.close();
        if (exit) widget.onExit();
      });
    }
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final active = widget.controller.active;
    return Stack(
      children: [
        ExcludeSemantics(excluding: active, child: widget.child),
        if (active) Positioned.fill(child: _buildPanel(context)),
      ],
    );
  }

  Widget _buildPanel(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    final colors = Theme.of(context).colorScheme;
    final textTheme = Theme.of(context).textTheme;
    final controller = widget.controller;
    final result = controller.result;
    final failed = result != null && result.outcome != SosOutcome.sent;
    return Material(
      color: colors.surface,
      child: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 420),
            child: Padding(
              padding: const EdgeInsets.all(24),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(l.sosAction, style: textTheme.titleLarge, textAlign: TextAlign.center),
                  const SizedBox(height: 24),
                  if (controller.sending) ...[
                    const CircularProgressIndicator(),
                    const SizedBox(height: 16),
                  ],
                  Text(
                    result == null ? l.sendingMessage : sosResultMessage(l, result),
                    style: textTheme.bodyLarge?.copyWith(color: failed ? colors.error : null),
                    textAlign: TextAlign.center,
                  ),
                  if (failed) ...[
                    const SizedBox(height: 24),
                    SizedBox(
                      width: double.infinity,
                      child: FilledButton.tonal(
                        style: FilledButton.styleFrom(
                          backgroundColor: colors.errorContainer,
                          foregroundColor: colors.onErrorContainer,
                        ),
                        onPressed: controller.send,
                        child: Text(l.quickSosRetryButton),
                      ),
                    ),
                    const SizedBox(height: 8),
                    TextButton(onPressed: controller.close, child: Text(l.quickSosCloseButton)),
                  ],
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}
