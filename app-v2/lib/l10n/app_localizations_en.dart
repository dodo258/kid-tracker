// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for English (`en`).
class AppLocalizationsEn extends AppLocalizations {
  AppLocalizationsEn([String locale = 'en']) : super(locale);

  @override
  String get trackingTitle => 'Tracking';

  @override
  String get settingsTitle => 'Settings';

  @override
  String get statusTitle => 'Logs';

  @override
  String get saveButton => 'Save';

  @override
  String get cancelButton => 'Cancel';

  @override
  String get okButton => 'OK';

  @override
  String get locationButton => 'Send location';

  @override
  String get statusButton => 'Show status';

  @override
  String get settingsButton => 'Change settings';

  @override
  String get invalidValue => 'Invalid value';

  @override
  String get disabledValue => 'Disabled';

  @override
  String get idLabel => 'Device identifier';

  @override
  String get urlLabel => 'Server URL';

  @override
  String get accuracyLabel => 'Location accuracy';

  @override
  String get highestAccuracyLabel => 'Highest';

  @override
  String get highAccuracyLabel => 'High';

  @override
  String get mediumAccuracyLabel => 'Medium';

  @override
  String get lowAccuracyLabel => 'Low';

  @override
  String get intervalLabel => 'Interval (seconds)';

  @override
  String get fastestIntervalLabel => 'Fastest interval (seconds)';

  @override
  String get distanceLabel => 'Distance (meters)';

  @override
  String get angleLabel => 'Angle (degrees)';

  @override
  String get heartbeatLabel => 'Stationary heartbeat (seconds)';

  @override
  String get bufferLabel => 'Offline buffering';

  @override
  String get wakelockLabel => 'Wake lock';

  @override
  String get stopDetectionLabel => 'Stop detection';

  @override
  String get preferPlatformProvidersLabel => 'Use system location';

  @override
  String get trackingLabel => 'Continuous tracking';

  @override
  String get advancedLabel => 'Advanced settings';

  @override
  String get passwordLabel => 'Password';

  @override
  String get optimizationMessage =>
      'To ensure reliable tracking, please disable battery optimization for this app.';

  @override
  String get passwordError => 'Wrong password';

  @override
  String get startAction => 'Start service';

  @override
  String get stopAction => 'Stop service';

  @override
  String get sosAction => 'Send SOS';

  @override
  String get disclosureMessage =>
      'This app collects location and activity data in the background and sends it to the configured server.';

  @override
  String get configurationMessage => 'Apply new configuration?';

  @override
  String get appTitle => 'Location Service';

  @override
  String get notificationText => 'Running';

  @override
  String get sendingMessage => 'Sending…';

  @override
  String get sosSentMessage => 'SOS sent to your parent';

  @override
  String get sosQueuedMessage =>
      'SOS not sent yet. It will be sent automatically when the network is back. Send it again, or call your parent.';

  @override
  String sendErrorMessage(String reason) {
    return 'Not sent: $reason';
  }

  @override
  String get sosConfirmTitle => 'Send SOS?';

  @override
  String get sosConfirmMessage =>
      'Your current location and an SOS will be sent to your parent.';

  @override
  String get sosConfirmButton => 'Send SOS';

  @override
  String get quickSosCloseButton => 'Close';

  @override
  String get quickSosRetryButton => 'Send SOS again';

  @override
  String askAiPrompt(String device, String item) {
    return 'My phone is $device. How do I turn on \"$item\" for the app \"定位服务\"?';
  }

  @override
  String get bufferHint => 'Off: locations recorded without network are lost';

  @override
  String get bufferOffWarning =>
      'With offline buffering off, locations recorded without network are dropped and your parent sees a gap. Turn it off anyway?';

  @override
  String get changePasswordButton => 'Change password';

  @override
  String get changePasswordHint => 'Then set a new one';

  @override
  String get checkExactAlarmNa => 'Not needed on this Android version';

  @override
  String get checklistAllDone => 'All done';

  @override
  String get checklistAutoSection => 'Checked automatically';

  @override
  String get checklistButton => 'Reliability settings';

  @override
  String checklistDeviceLabel(String device) {
    return 'This phone: $device';
  }

  @override
  String get checklistIntroAuto =>
      'Everything is checked automatically, nothing to tick by hand. The page re-reads when you come back; long-press a row to copy a question for an AI';

  @override
  String checklistNotNeededSection(int count) {
    return 'Not needed on this phone ($count)';
  }

  @override
  String checklistPending(int count) {
    return '$count still being verified';
  }

  @override
  String checklistRemaining(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count items left',
      one: '1 item left',
    );
    return '$_temp0';
  }

  @override
  String get checklistSuggestSection => 'Suggested (doesn\'t block tracking)';

  @override
  String get checklistTitle => 'Settings for reliable tracking';

  @override
  String get checklistVendorNote =>
      'The system can\'t report these. While tracking is on, the app judges them by whether it recovers after a reboot, reports on time with the screen locked, and can send with the screen locked';

  @override
  String get checklistVendorSection =>
      'Phone maker settings (judged by behaviour)';

  @override
  String get confirmPasswordHint => 'Same digits as before';

  @override
  String get confirmPasswordTitle => 'Enter it again';

  @override
  String get copiedMessage => 'Copied. Paste it into an AI assistant.';

  @override
  String get copyButton => 'Copy';

  @override
  String get currentPasswordTitle => 'Enter the current password';

  @override
  String get deleteKey => 'Delete';

  @override
  String get disablePasswordButton => 'Turn off password';

  @override
  String get disablePasswordHint =>
      'Enter the current password to confirm. The app will then open without one.';

  @override
  String get disablePasswordTitle => 'Turn off the app password';

  @override
  String fallbackPath(String path) {
    return 'Could not open it directly, opened the nearest settings page instead. Look for: $path';
  }

  @override
  String get goToEnableButton => 'Turn on';

  @override
  String get goToSettingsButton => 'Open settings';

  @override
  String get guardAdminNotActivated =>
      'Uninstall protection was not activated.';

  @override
  String get guardAdminTitle => 'Uninstall protection (device admin)';

  @override
  String get guardAdminWhy =>
      'The child cannot uninstall this app until it is turned off here with the password.';

  @override
  String get guardLost =>
      'It was turned off; turn it on again (an alarm is sent to the parent when this happens).';

  @override
  String get guardNeedPassword =>
      'Set an app password first: it is needed to turn the protection off.';

  @override
  String get guardSection => 'Protection';

  @override
  String get idCopiedMessage => 'Device identifier copied';

  @override
  String itemAssociatedLaunch(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': 'Secondary launch'});
    return '$_temp0';
  }

  @override
  String itemAssociatedLaunchPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App launch → this app → Secondary launch',
    });
    return '$_temp0';
  }

  @override
  String get itemAssociatedLaunchWhy =>
      'Lets other system components wake the app, so it recovers faster.';

  @override
  String itemAutostart(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': 'Auto-launch',
      'honor': 'Auto-launch',
      'other': 'Autostart',
    });
    return '$_temp0';
  }

  @override
  String itemAutostartPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei':
          'Phone Manager → App launch → this app → turn off Manage automatically → Auto-launch',
      'honor':
          'Phone Manager → App launch → this app → turn off Manage automatically → Auto-launch',
      'other':
          'Look for Autostart in app info or the security app; if your system has none, just tick it.',
    });
    return '$_temp0';
  }

  @override
  String get itemAutostartWhy =>
      'Lets the app start itself again after it is stopped or the phone restarts.';

  @override
  String itemBackgroundActivity(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Run in background',
    });
    return '$_temp0';
  }

  @override
  String itemBackgroundActivityPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App launch → this app → Run in background',
    });
    return '$_temp0';
  }

  @override
  String get itemBackgroundActivityWhy =>
      'Keeps reporting after the screen locks.';

  @override
  String itemBackgroundLocation(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Allow all the time',
    });
    return '$_temp0';
  }

  @override
  String itemBackgroundLocationPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App info → Permissions → Location → Allow all the time',
    });
    return '$_temp0';
  }

  @override
  String get itemBackgroundLocationWhy =>
      'Otherwise the app gets no location once it is in the background.';

  @override
  String itemBackgroundRestriction(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Not \"Restricted\"',
    });
    return '$_temp0';
  }

  @override
  String itemBackgroundRestrictionPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App info → App battery usage → do not choose Restricted',
    });
    return '$_temp0';
  }

  @override
  String get itemBackgroundRestrictionWhy =>
      'When restricted, the app cannot run in the background at all.';

  @override
  String itemBatteryOptimization(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'samsung': 'Unrestricted',
      'other': 'Unrestricted',
    });
    return '$_temp0';
  }

  @override
  String itemBatteryOptimizationPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App info → App battery usage → Unrestricted',
    });
    return '$_temp0';
  }

  @override
  String get itemBatteryOptimizationWhy =>
      'Battery optimization pauses the app after the screen locks.';

  @override
  String itemDataSaver(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Data saver: unrestricted for this app',
    });
    return '$_temp0';
  }

  @override
  String itemDataSaverPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other':
          'Settings → Network → Data saver → Unrestricted data → turn on this app',
    });
    return '$_temp0';
  }

  @override
  String get itemDataSaverWhy =>
      'With data saver on, reports may not get through.';

  @override
  String itemExactAlarm(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Alarms & reminders',
    });
    return '$_temp0';
  }

  @override
  String itemExactAlarmPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App info → Alarms & reminders',
    });
    return '$_temp0';
  }

  @override
  String get itemExactAlarmWhy => 'Lets heartbeats fire on time.';

  @override
  String itemHibernation(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Pause app activity if unused: off',
    });
    return '$_temp0';
  }

  @override
  String itemHibernationPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App info → Pause app activity if unused → off',
    });
    return '$_temp0';
  }

  @override
  String get itemHibernationWhy =>
      'If on, the app is paused and loses its permissions when not opened for months.';

  @override
  String itemLocationService(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': 'Use location'});
    return '$_temp0';
  }

  @override
  String itemLocationServicePath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Settings → Location → Use location',
    });
    return '$_temp0';
  }

  @override
  String get itemLocationServiceWhy =>
      'The system-wide location switch; when off, no app can get a location.';

  @override
  String itemMotionPermission(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Allow physical activity',
    });
    return '$_temp0';
  }

  @override
  String itemMotionPermissionPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App info → Permissions → Physical activity → Allow',
    });
    return '$_temp0';
  }

  @override
  String get itemMotionPermissionWhy =>
      'Without it walking and riding are noticed minutes later.';

  @override
  String itemNetworkAccess(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Network access: mobile data and Wi-Fi allowed',
    });
    return '$_temp0';
  }

  @override
  String itemNetworkAccessPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei':
          'Phone Manager → Data usage → Network access → this app → allow Mobile data and Wi-Fi',
      'honor':
          'Phone Manager → Data usage → Network access → this app → allow Mobile data and Wi-Fi',
      'xiaomi': 'App info → Restrict data usage → allow both',
      'vivo': 'Search Settings for \"Network access\" → this app → allow both',
      'other':
          'Search Settings for \"network\" → this app → allow Mobile data and Wi-Fi',
    });
    return '$_temp0';
  }

  @override
  String get itemNetworkAccessWhy =>
      'Without it the app can locate but not send.';

  @override
  String itemNotifications(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Allow notifications',
    });
    return '$_temp0';
  }

  @override
  String itemNotificationsPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App info → Notifications → Allow notifications',
    });
    return '$_temp0';
  }

  @override
  String get itemNotificationsWhy =>
      'Without the tracking notification the service is killed more easily.';

  @override
  String itemPowerSave(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Battery saver: off',
    });
    return '$_temp0';
  }

  @override
  String itemPowerSavePath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Settings → Battery → turn off Battery saver',
    });
    return '$_temp0';
  }

  @override
  String get itemPowerSaveWhy =>
      'Turn battery saver off, and don\'t let it turn on automatically at low battery.';

  @override
  String itemPowerSaving(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'xiaomi': 'Battery saver: No restrictions',
      'samsung': 'Never sleeping apps',
      'vivo': 'Allow high background power consumption',
      'other': 'Allow background activity',
    });
    return '$_temp0';
  }

  @override
  String itemPowerSavingPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'xiaomi': 'App info → Battery saver → No restrictions',
      'samsung':
          'Settings → Battery → Background usage limits → Never sleeping apps → add this app',
      'vivo':
          'Settings → Battery → Background power consumption management → this app → Allow',
      'other': 'App info → Battery usage → Allow background activity',
    });
    return '$_temp0';
  }

  @override
  String itemPowerSavingWhy(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other':
          'The manufacturer\'s own power saving stops background apps separately from Android\'s battery optimization.',
    });
    return '$_temp0';
  }

  @override
  String itemPreciseLocation(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Use precise location',
    });
    return '$_temp0';
  }

  @override
  String itemPreciseLocationPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'App info → Permissions → Location → Use precise location',
    });
    return '$_temp0';
  }

  @override
  String get itemPreciseLocationWhy =>
      'With approximate location only, the position can be off by kilometres.';

  @override
  String itemRecentsLock(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': 'Lock'});
    return '$_temp0';
  }

  @override
  String itemRecentsLockPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other':
          'While this app is open, open recent apps and lock its card (long-press or pull it down, choose Lock). Once you leave the app its card is hidden there.',
    });
    return '$_temp0';
  }

  @override
  String get itemRecentsLockWhy =>
      '\"Clear all\" in recent apps will not close it.';

  @override
  String itemScreenOffNetwork(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': 'Stay connected during sleep',
      'honor': 'Stay connected during sleep',
      'xiaomi': 'Turn off mobile data when locked: never',
      'other': 'Sleep standby optimization: off',
    });
    return '$_temp0';
  }

  @override
  String itemScreenOffNetworkPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei':
          'Settings → Battery → More battery settings → turn on \"Stay connected during sleep\"',
      'honor':
          'Settings → Battery → More battery settings → turn on \"Stay connected during sleep\"',
      'xiaomi':
          'Settings → Battery → More settings → \"Turn off mobile data when device is locked\" → Never',
      'oppo':
          'Settings → Battery → More settings → turn off \"Sleep standby optimization\"',
      'vivo': 'Settings → Battery → turn off \"Sleep standby optimization\"',
      'other':
          'Search Settings for \"sleep\" and keep the network on when locked',
    });
    return '$_temp0';
  }

  @override
  String get itemScreenOffNetworkWhy =>
      'Otherwise the network drops soon after locking.';

  @override
  String itemWifiScan(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': 'Wi-Fi scanning'});
    return '$_temp0';
  }

  @override
  String itemWifiScanPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': 'Search Settings for \"Wi-Fi scanning\" and turn it on',
    });
    return '$_temp0';
  }

  @override
  String get itemWifiScanWhy =>
      'Without it, indoor locations can be off by hundreds of meters or more.';

  @override
  String get lastReportLabel => 'Last successful report';

  @override
  String get lastReportNever => 'No successful report yet';

  @override
  String get locationFailedMessage =>
      'Location not sent: maybe no network or no fix yet. Try again in a moment.';

  @override
  String get locationSentMessage => 'Location sent to your parent';

  @override
  String get locationSourceAutoLabel => 'Auto (prefer AMap)';

  @override
  String get locationSourceHint =>
      'System only: AMap is not used, indoor fixes often fail';

  @override
  String get locationSourceLabel => 'Location source';

  @override
  String get locationSourceSystemLabel => 'System location only';

  @override
  String get lockHint =>
      'This is the app\'s own password, not the phone\'s screen lock';

  @override
  String get lockTitle => 'Enter the app password';

  @override
  String get newPasswordTitle => 'Enter a new password';

  @override
  String get notApplicableMessage => 'Not available on this Android version';

  @override
  String get passwordChanged => 'Password changed';

  @override
  String get passwordDisabled => 'App password turned off';

  @override
  String get passwordItem => 'App password';

  @override
  String passwordLockedOut(int count) {
    return '$count wrong tries in a row. The app will close.';
  }

  @override
  String get passwordMismatch => 'They didn\'t match. Start again.';

  @override
  String get passwordNotSet => 'Not set';

  @override
  String passwordRemaining(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count tries left',
      one: '1 try left',
    );
    return '$_temp0';
  }

  @override
  String get passwordWhy =>
      'Once set, the app asks for it every time it is opened, so the child cannot turn off tracking or change settings. This is the app\'s own password, unrelated to the phone\'s screen lock.';

  @override
  String passwordWrongRemaining(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: 'Wrong password, $count tries left',
      one: 'Wrong password, 1 try left',
    );
    return '$_temp0';
  }

  @override
  String get pendingActionConfig => 'Change server and device settings';

  @override
  String get pendingActionGeneric =>
      'The previous action needs the password. Continue now?';

  @override
  String get pendingActionStart => 'Turn tracking on';

  @override
  String get pendingActionStop => 'Stop tracking (the parent will be notified)';

  @override
  String get pendingActionTitle => 'Continue?';

  @override
  String get promptFirstRun =>
      'First time here: set up the settings needed for reliable tracking, or the system may stop the app in the background.';

  @override
  String promptIncomplete(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count settings for reliable tracking are not done',
      one: '1 setting for reliable tracking is not done',
    );
    return '$_temp0; the system may stop the app in the background.';
  }

  @override
  String get promptNoPassword =>
      'No app password: the child can turn off tracking';

  @override
  String get promptOpenButton => 'Open checklist';

  @override
  String get promptSetPasswordButton => 'Set password';

  @override
  String get promptSystemUpdated =>
      'The system was updated and may have reset some settings. Open the checklist and confirm again.';

  @override
  String get recorderClearButton => 'Delete old';

  @override
  String get recorderExportTitle => 'Export recordings';

  @override
  String recorderOff(int files, String mb) {
    return 'Records sensors, Wi-Fi and location every 2 seconds, kept on this phone only. $files files, $mb MB';
  }

  @override
  String recorderOn(int rows, int files, String mb) {
    return 'Recording: $rows rows this time, $files files, $mb MB. Uses more battery, turn off when done';
  }

  @override
  String get recorderRefreshButton => 'Refresh';

  @override
  String get recorderShareButton => 'Share';

  @override
  String recorderStopped(String reason) {
    return 'Recording stopped: $reason';
  }

  @override
  String get recorderTitle => 'Sensor recording (testing)';

  @override
  String get reportOverdueMessage =>
      'Nothing reported for over 3 heartbeat periods. Check the reliability settings.';

  @override
  String get scanConfigTooltip => 'Scan configuration';

  @override
  String get sendNoPermissionMessage =>
      'Can\'t send without location permission. Allow it in system settings.';

  @override
  String get setPasswordButton => 'Set';

  @override
  String setPasswordHint(int min, int max) {
    return '$min to $max digits';
  }

  @override
  String get setPasswordTitle => 'Set the app password';

  @override
  String get startFailedMessage =>
      'Failed to start tracking. Check location permissions.';

  @override
  String get techChangeConfirmButton => 'Turn off anyway';

  @override
  String get techChangeTitle => 'This affects reliability';

  @override
  String get techOptionsTitle => 'Technical options (usually leave alone)';

  @override
  String trackingBlocked(int count) {
    String _temp0 = intl.Intl.pluralLogic(
      count,
      locale: localeName,
      other: '$count settings are not done yet',
      one: '1 setting is not done yet',
    );
    return '$_temp0. Tick every item in the checklist before turning on tracking.';
  }

  @override
  String get updateAllowInstall =>
      'Allow this app to install updates, then come back to install';

  @override
  String get updateAvailable => 'New version available, tap to update';

  @override
  String get updateChecksumFailed =>
      'The downloaded file doesn\'t match, not installing';

  @override
  String get updateDownloadFailed =>
      'Download failed. Check the network and tap again';

  @override
  String get updateDownloading => 'Downloading new version';

  @override
  String get updateIdentityFailed =>
      'The downloaded file is not an official build of this app, not installed';

  @override
  String get updateInstallFailed => 'Couldn\'t open the installer';

  @override
  String get updateReady => 'Downloaded, tap to install';

  @override
  String get vendorBadBreath =>
      'Not working: reports are late with the screen locked';

  @override
  String vendorBadBreathMinutes(int minutes) {
    return 'Not working: reports were $minutes min late with the screen locked';
  }

  @override
  String get vendorBadReboot =>
      'Not working: did not resume after a reboot until the app was opened';

  @override
  String get vendorBadSend =>
      'Not working: can\'t send with the screen locked, only after it turns on';

  @override
  String get vendorOkBreath =>
      'Working: reports on time with the screen locked';

  @override
  String get vendorOkReboot => 'Working: resumed by itself after a reboot';

  @override
  String get vendorOkSend => 'Working: can send with the screen locked';

  @override
  String get vendorPending => 'Not verified yet: ';

  @override
  String get vendorPendingBreath =>
      'leave the phone locked for 15+ minutes with tracking on, then check back';

  @override
  String get vendorPendingReboot =>
      'restart the phone with tracking on, don\'t open the app, check back in a few minutes';

  @override
  String get vendorPendingSend =>
      'leave the phone locked for a while with tracking on, then check back';

  @override
  String get vendorRecheckButton => 'Fixed it';

  @override
  String get verifyHint => 'A parent needs to enter it to continue';

  @override
  String get wakelockHint =>
      'Usually off. On: keeps the phone awake when locked, uses more battery';

  @override
  String get faultSection => 'Run log';

  @override
  String get faultNote =>
      'The latest problem the app ran into while reporting and how it handled it';

  @override
  String get faultNone => 'No problems recorded';

  @override
  String get faultRescue =>
      'Reporting got stuck and the app restarted location or tracking';

  @override
  String get faultDegraded =>
      'AMap location failed; system location is used for this session';

  @override
  String get faultStorage =>
      'Storage couldn\'t be written (maybe full); points are kept in memory and still sent';

  @override
  String get faultDropped => 'Too many unsent records; the oldest were dropped';

  @override
  String get faultCrash => 'A background error happened and was recovered';
}
