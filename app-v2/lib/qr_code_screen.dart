import 'package:app_settings/app_settings.dart';
import 'package:flutter/material.dart';
import 'package:flutter_zxing/flutter_zxing.dart';

import 'configuration_service.dart';
import 'l10n/app_localizations.dart';

class QrCodeScreen extends StatefulWidget {
  const QrCodeScreen({super.key});

  @override
  State<QrCodeScreen> createState() => _QrCodeScreenState();
}

class _QrCodeScreenState extends State<QrCodeScreen> {
  bool _scanned = false;
  bool _cameraError = false;

  void _onScan(Code code) async {
    if (_scanned) return;
    final rawValue = code.text;
    if (!code.isValid || rawValue == null) return;
    // 只认本 App 的配置码，普通网址、别的码都忽略、接着扫（审计 S4）
    final uri = ConfigurationService.parseConfigCode(rawValue);
    if (uri == null) return;
    _scanned = true;
    await ConfigurationService.applyUri(uri);
    if (mounted) Navigator.pop(context);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: Text(AppLocalizations.of(context)!.settingsTitle),
      ),
      body: _cameraError ? _buildError(context) : ReaderWidget(
        codeFormat: Format.qrCode,
        showGallery: false,
        showToggleCamera: false,
        onScan: _onScan,
        onControllerCreated: (_, error) {
          if (error != null && mounted) setState(() => _cameraError = true);
        },
      ),
    );
  }

  Widget _buildError(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(24.0),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.videocam_off_outlined, size: 120),
            Text(AppLocalizations.of(context)!.disabledValue),
            SizedBox(height: 24,),
            FilledButton.tonal(
              onPressed:
                  () => AppSettings.openAppSettings(
                    type: AppSettingsType.settings,
                  ),
              child: Text(AppLocalizations.of(context)!.settingsTitle),
            ),
          ],
        ),
      ),
    );
  }
}
