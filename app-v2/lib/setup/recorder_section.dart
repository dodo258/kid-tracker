import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:share_plus/share_plus.dart';

import '../l10n/app_localizations.dart';

/// 测试用的传感器录制（主人 2026-11-17 起用它采真实数据调判断层）：只写本机文件，不上传；开着比平时费电，测完关掉。
/// 录制在 SDK（sense/SnapshotRecorder.kt）：打不开文件、写失败会自己停下、关掉开关，这里显示原因（审计 M18）。
class RecorderSection extends StatefulWidget {
  const RecorderSection({super.key});

  @override
  State<RecorderSection> createState() => _RecorderSectionState();
}

class _RecorderSectionState extends State<RecorderSection> {
  static const _channel = MethodChannel('com.dodo258.tracker/recorder');
  Map<Object?, Object?>? _status;

  @override
  void initState() {
    super.initState();
    _call('status');
  }

  Future<void> _call(String method, [Object? arguments]) async {
    final status = await _channel.invokeMethod<Map<Object?, Object?>>(method, arguments);
    if (mounted) setState(() => _status = status);
  }

  Future<void> _share() async {
    final files = await _channel.invokeListMethod<String>('files') ?? const [];
    if (files.isEmpty || !mounted) return;
    final box = context.findRenderObject() as RenderBox?;
    await SharePlus.instance.share(ShareParams(
      files: files.map(XFile.new).toList(),
      sharePositionOrigin: box == null ? null : box.localToGlobal(Offset.zero) & box.size,
    ));
  }

  @override
  Widget build(BuildContext context) {
    final l = AppLocalizations.of(context)!;
    final status = _status;
    if (status == null) return const SizedBox.shrink();
    final on = status['on'] == true;
    final error = status['error'] as String?;
    final files = (status['files'] as num?)?.toInt() ?? 0;
    final mb = (((status['bytes'] as num?) ?? 0) / 1048576).toStringAsFixed(1);
    final rows = (status['rows'] as num?)?.toInt() ?? 0;
    return Column(
      children: [
        SwitchListTile(
          title: Text(l.recorderTitle),
          subtitle: Text.rich(TextSpan(children: [
            TextSpan(text: on ? l.recorderOn(rows, files, mb) : l.recorderOff(files, mb)),
            if (error != null)
              TextSpan(
                text: '\n${l.recorderStopped(error)}',
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
          ])),
          value: on,
          onChanged: (value) => _call('setOn', value),
        ),
        ListTile(
          title: Text(l.recorderExportTitle),
          subtitle: Wrap(spacing: 8, children: [
            OutlinedButton(onPressed: () => _call('status'), child: Text(l.recorderRefreshButton)),
            OutlinedButton(onPressed: _share, child: Text(l.recorderShareButton)),
            OutlinedButton(onPressed: () => _call('clear'), child: Text(l.recorderClearButton)),
          ]),
        ),
      ],
    );
  }
}
