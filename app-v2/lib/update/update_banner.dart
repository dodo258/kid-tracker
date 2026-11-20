import 'dart:io';

import 'package:flutter/material.dart';

import '../l10n/app_localizations.dart';
import 'apk_updater.dart';
import 'app_update.dart';

enum _Stage { idle, downloading, ready }

/// 首页顶部的「有新版本」横条：点了在 App 里下载（显示进度）、核对 sha256，下好弹系统安装确认。
/// 没允许「安装未知应用」就先跳去开，回到 App 自动接着装。同签名覆盖安装，配置保留。
class UpdateBanner extends StatefulWidget {
  const UpdateBanner({super.key, required this.info, this.updater});

  final UpdateInfo info;

  /// 测试注入；默认用全局的 [apkUpdater]。
  final ApkUpdater? updater;

  @override
  State<UpdateBanner> createState() => _UpdateBannerState();
}

class _UpdateBannerState extends State<UpdateBanner> with WidgetsBindingObserver {
  _Stage _stage = _Stage.idle;
  double? _progress;
  File? _apk;
  bool _waitingPermission = false;

  ApkUpdater get _updater => widget.updater ?? apkUpdater;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void didUpdateWidget(UpdateBanner oldWidget) {
    super.didUpdateWidget(oldWidget);
    // 服务器又出了更新的版本：下好的旧包作废
    if (oldWidget.info.versionCode != widget.info.versionCode && _stage == _Stage.ready) {
      setState(() => _stage = _Stage.idle);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  /// 从「安装未知应用」页回来：开了就接着装。
  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state != AppLifecycleState.resumed || !_waitingPermission) return;
    _waitingPermission = false;
    _updater.canInstall().then((allowed) {
      if (allowed && mounted && _stage == _Stage.ready) _install();
    });
  }

  void _tap() {
    switch (_stage) {
      case _Stage.idle:
        _download();
      case _Stage.downloading:
        break;
      case _Stage.ready:
        _install();
    }
  }

  Future<void> _download() async {
    final texts = AppLocalizations.of(context)!;
    setState(() {
      _stage = _Stage.downloading;
      _progress = null;
    });
    try {
      final apk = await _updater.download(widget.info, onProgress: _onProgress);
      if (!mounted) return;
      setState(() {
        _apk = apk;
        _stage = _Stage.ready;
      });
      await _install();
    } on ApkDownloadException catch (e) {
      if (!mounted) return;
      setState(() => _stage = _Stage.idle);
      _say(e.failure == DownloadFailure.checksum ? texts.updateChecksumFailed : texts.updateDownloadFailed);
    } catch (_) {
      // 拿不到缓存目录等
      if (!mounted) return;
      setState(() => _stage = _Stage.idle);
      _say(texts.updateDownloadFailed);
    }
  }

  /// 进度只在整百分比变化时刷新，免得每个数据块都重绘。
  void _onProgress(double? progress) {
    if (!mounted) return;
    final percent = progress == null ? null : (progress * 100).floor();
    final current = _progress == null ? null : (_progress! * 100).floor();
    if (percent != current) setState(() => _progress = progress);
  }

  Future<void> _install() async {
    final texts = AppLocalizations.of(context)!;
    if (!await _updater.canInstall()) {
      _waitingPermission = true;
      _say(texts.updateAllowInstall);
      if (!await _updater.openInstallSettings()) {
        _waitingPermission = false;
        _say(texts.updateInstallFailed);
      }
      return;
    }
    // 交给系统前核对是不是本 App、同一张签名、更新的版本（审计 M1）；不对就删掉，不弹安装框
    final problem = await _updater.verify(_apk!);
    if (problem != null) {
      await _updater.clear();
      if (!mounted) return;
      setState(() => _stage = _Stage.idle);
      _say(texts.updateIdentityFailed);
      return;
    }
    if (await _updater.install(_apk!)) return;
    // 缓存被系统清掉、没有安装器：回到起点，再点重新下
    if (!mounted) return;
    setState(() => _stage = _Stage.idle);
    _say(texts.updateInstallFailed);
  }

  void _say(String message) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(message)));
  }

  @override
  Widget build(BuildContext context) {
    final texts = AppLocalizations.of(context)!;
    final colors = Theme.of(context).colorScheme;
    final style = TextStyle(color: colors.onPrimaryContainer);
    final info = widget.info;
    final detail = info.notes.isNotEmpty ? info.notes : info.versionName;
    final Widget? subtitle;
    if (_stage == _Stage.downloading) {
      subtitle = Padding(
        padding: const EdgeInsets.only(top: 8),
        child: LinearProgressIndicator(value: _progress),
      );
    } else {
      subtitle = detail.isEmpty ? null : Text(detail, style: style);
    }
    return Card(
      color: colors.primaryContainer,
      child: ListTile(
        leading: Icon(Icons.system_update, color: colors.onPrimaryContainer),
        title: Text(
          switch (_stage) {
            _Stage.idle => texts.updateAvailable,
            _Stage.downloading => texts.updateDownloading,
            _Stage.ready => texts.updateReady,
          },
          style: style,
        ),
        subtitle: subtitle,
        trailing: _stage == _Stage.downloading && _progress != null
            ? Text('${(_progress! * 100).floor()}%', style: style)
            : null,
        onTap: _tap,
      ),
    );
  }
}
