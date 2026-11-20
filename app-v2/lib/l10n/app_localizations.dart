import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:intl/intl.dart' as intl;

import 'app_localizations_en.dart';
import 'app_localizations_zh.dart';

// ignore_for_file: type=lint

/// Callers can lookup localized strings with an instance of AppLocalizations
/// returned by `AppLocalizations.of(context)`.
///
/// Applications need to include `AppLocalizations.delegate()` in their app's
/// `localizationDelegates` list, and the locales they support in the app's
/// `supportedLocales` list. For example:
///
/// ```dart
/// import 'l10n/app_localizations.dart';
///
/// return MaterialApp(
///   localizationsDelegates: AppLocalizations.localizationsDelegates,
///   supportedLocales: AppLocalizations.supportedLocales,
///   home: MyApplicationHome(),
/// );
/// ```
///
/// ## Update pubspec.yaml
///
/// Please make sure to update your pubspec.yaml to include the following
/// packages:
///
/// ```yaml
/// dependencies:
///   # Internationalization support.
///   flutter_localizations:
///     sdk: flutter
///   intl: any # Use the pinned version from flutter_localizations
///
///   # Rest of dependencies
/// ```
///
/// ## iOS Applications
///
/// iOS applications define key application metadata, including supported
/// locales, in an Info.plist file that is built into the application bundle.
/// To configure the locales supported by your app, you’ll need to edit this
/// file.
///
/// First, open your project’s ios/Runner.xcworkspace Xcode workspace file.
/// Then, in the Project Navigator, open the Info.plist file under the Runner
/// project’s Runner folder.
///
/// Next, select the Information Property List item, select Add Item from the
/// Editor menu, then select Localizations from the pop-up menu.
///
/// Select and expand the newly-created Localizations item then, for each
/// locale your application supports, add a new item and select the locale
/// you wish to add from the pop-up menu in the Value field. This list should
/// be consistent with the languages listed in the AppLocalizations.supportedLocales
/// property.
abstract class AppLocalizations {
  AppLocalizations(String locale)
    : localeName = intl.Intl.canonicalizedLocale(locale.toString());

  final String localeName;

  static AppLocalizations? of(BuildContext context) {
    return Localizations.of<AppLocalizations>(context, AppLocalizations);
  }

  static const LocalizationsDelegate<AppLocalizations> delegate =
      _AppLocalizationsDelegate();

  /// A list of this localizations delegate along with the default localizations
  /// delegates.
  ///
  /// Returns a list of localizations delegates containing this delegate along with
  /// GlobalMaterialLocalizations.delegate, GlobalCupertinoLocalizations.delegate,
  /// and GlobalWidgetsLocalizations.delegate.
  ///
  /// Additional delegates can be added by appending to this list in
  /// MaterialApp. This list does not have to be used at all if a custom list
  /// of delegates is preferred or required.
  static const List<LocalizationsDelegate<dynamic>> localizationsDelegates =
      <LocalizationsDelegate<dynamic>>[
        delegate,
        GlobalMaterialLocalizations.delegate,
        GlobalCupertinoLocalizations.delegate,
        GlobalWidgetsLocalizations.delegate,
      ];

  /// A list of this localizations delegate's supported locales.
  static const List<Locale> supportedLocales = <Locale>[
    Locale('zh'),
    Locale('en'),
  ];

  /// No description provided for @trackingTitle.
  ///
  /// In en, this message translates to:
  /// **'Tracking'**
  String get trackingTitle;

  /// No description provided for @settingsTitle.
  ///
  /// In en, this message translates to:
  /// **'Settings'**
  String get settingsTitle;

  /// No description provided for @statusTitle.
  ///
  /// In en, this message translates to:
  /// **'Logs'**
  String get statusTitle;

  /// No description provided for @saveButton.
  ///
  /// In en, this message translates to:
  /// **'Save'**
  String get saveButton;

  /// No description provided for @cancelButton.
  ///
  /// In en, this message translates to:
  /// **'Cancel'**
  String get cancelButton;

  /// No description provided for @okButton.
  ///
  /// In en, this message translates to:
  /// **'OK'**
  String get okButton;

  /// No description provided for @locationButton.
  ///
  /// In en, this message translates to:
  /// **'Send location'**
  String get locationButton;

  /// No description provided for @statusButton.
  ///
  /// In en, this message translates to:
  /// **'Show status'**
  String get statusButton;

  /// No description provided for @settingsButton.
  ///
  /// In en, this message translates to:
  /// **'Change settings'**
  String get settingsButton;

  /// No description provided for @invalidValue.
  ///
  /// In en, this message translates to:
  /// **'Invalid value'**
  String get invalidValue;

  /// No description provided for @disabledValue.
  ///
  /// In en, this message translates to:
  /// **'Disabled'**
  String get disabledValue;

  /// No description provided for @idLabel.
  ///
  /// In en, this message translates to:
  /// **'Device identifier'**
  String get idLabel;

  /// No description provided for @urlLabel.
  ///
  /// In en, this message translates to:
  /// **'Server URL'**
  String get urlLabel;

  /// No description provided for @accuracyLabel.
  ///
  /// In en, this message translates to:
  /// **'Location accuracy'**
  String get accuracyLabel;

  /// No description provided for @highestAccuracyLabel.
  ///
  /// In en, this message translates to:
  /// **'Highest'**
  String get highestAccuracyLabel;

  /// No description provided for @highAccuracyLabel.
  ///
  /// In en, this message translates to:
  /// **'High'**
  String get highAccuracyLabel;

  /// No description provided for @mediumAccuracyLabel.
  ///
  /// In en, this message translates to:
  /// **'Medium'**
  String get mediumAccuracyLabel;

  /// No description provided for @lowAccuracyLabel.
  ///
  /// In en, this message translates to:
  /// **'Low'**
  String get lowAccuracyLabel;

  /// No description provided for @intervalLabel.
  ///
  /// In en, this message translates to:
  /// **'Interval (seconds)'**
  String get intervalLabel;

  /// No description provided for @fastestIntervalLabel.
  ///
  /// In en, this message translates to:
  /// **'Fastest interval (seconds)'**
  String get fastestIntervalLabel;

  /// No description provided for @distanceLabel.
  ///
  /// In en, this message translates to:
  /// **'Distance (meters)'**
  String get distanceLabel;

  /// No description provided for @angleLabel.
  ///
  /// In en, this message translates to:
  /// **'Angle (degrees)'**
  String get angleLabel;

  /// No description provided for @heartbeatLabel.
  ///
  /// In en, this message translates to:
  /// **'Stationary heartbeat (seconds)'**
  String get heartbeatLabel;

  /// No description provided for @bufferLabel.
  ///
  /// In en, this message translates to:
  /// **'Offline buffering'**
  String get bufferLabel;

  /// No description provided for @wakelockLabel.
  ///
  /// In en, this message translates to:
  /// **'Wake lock'**
  String get wakelockLabel;

  /// No description provided for @stopDetectionLabel.
  ///
  /// In en, this message translates to:
  /// **'Stop detection'**
  String get stopDetectionLabel;

  /// No description provided for @preferPlatformProvidersLabel.
  ///
  /// In en, this message translates to:
  /// **'Use system location'**
  String get preferPlatformProvidersLabel;

  /// No description provided for @trackingLabel.
  ///
  /// In en, this message translates to:
  /// **'Continuous tracking'**
  String get trackingLabel;

  /// No description provided for @advancedLabel.
  ///
  /// In en, this message translates to:
  /// **'Advanced settings'**
  String get advancedLabel;

  /// No description provided for @passwordLabel.
  ///
  /// In en, this message translates to:
  /// **'Password'**
  String get passwordLabel;

  /// No description provided for @optimizationMessage.
  ///
  /// In en, this message translates to:
  /// **'To ensure reliable tracking, please disable battery optimization for this app.'**
  String get optimizationMessage;

  /// No description provided for @passwordError.
  ///
  /// In en, this message translates to:
  /// **'Wrong password'**
  String get passwordError;

  /// No description provided for @startAction.
  ///
  /// In en, this message translates to:
  /// **'Start service'**
  String get startAction;

  /// No description provided for @stopAction.
  ///
  /// In en, this message translates to:
  /// **'Stop service'**
  String get stopAction;

  /// No description provided for @sosAction.
  ///
  /// In en, this message translates to:
  /// **'Send SOS'**
  String get sosAction;

  /// No description provided for @disclosureMessage.
  ///
  /// In en, this message translates to:
  /// **'This app collects location and activity data in the background and sends it to the configured server.'**
  String get disclosureMessage;

  /// No description provided for @configurationMessage.
  ///
  /// In en, this message translates to:
  /// **'Apply new configuration?'**
  String get configurationMessage;

  /// No description provided for @appTitle.
  ///
  /// In en, this message translates to:
  /// **'Location Service'**
  String get appTitle;

  /// No description provided for @notificationText.
  ///
  /// In en, this message translates to:
  /// **'Running'**
  String get notificationText;

  /// No description provided for @sendingMessage.
  ///
  /// In en, this message translates to:
  /// **'Sending…'**
  String get sendingMessage;

  /// No description provided for @sosSentMessage.
  ///
  /// In en, this message translates to:
  /// **'SOS sent to your parent'**
  String get sosSentMessage;

  /// No description provided for @sosQueuedMessage.
  ///
  /// In en, this message translates to:
  /// **'SOS not sent yet. It will be sent automatically when the network is back. Send it again, or call your parent.'**
  String get sosQueuedMessage;

  /// No description provided for @sendErrorMessage.
  ///
  /// In en, this message translates to:
  /// **'Not sent: {reason}'**
  String sendErrorMessage(String reason);

  /// No description provided for @sosConfirmTitle.
  ///
  /// In en, this message translates to:
  /// **'Send SOS?'**
  String get sosConfirmTitle;

  /// No description provided for @sosConfirmMessage.
  ///
  /// In en, this message translates to:
  /// **'Your current location and an SOS will be sent to your parent.'**
  String get sosConfirmMessage;

  /// No description provided for @sosConfirmButton.
  ///
  /// In en, this message translates to:
  /// **'Send SOS'**
  String get sosConfirmButton;

  /// No description provided for @quickSosCloseButton.
  ///
  /// In en, this message translates to:
  /// **'Close'**
  String get quickSosCloseButton;

  /// No description provided for @quickSosRetryButton.
  ///
  /// In en, this message translates to:
  /// **'Send SOS again'**
  String get quickSosRetryButton;

  /// No description provided for @askAiPrompt.
  ///
  /// In en, this message translates to:
  /// **'My phone is {device}. How do I turn on \"{item}\" for the app \"定位服务\"?'**
  String askAiPrompt(String device, String item);

  /// No description provided for @bufferHint.
  ///
  /// In en, this message translates to:
  /// **'Off: locations recorded without network are lost'**
  String get bufferHint;

  /// No description provided for @bufferOffWarning.
  ///
  /// In en, this message translates to:
  /// **'With offline buffering off, locations recorded without network are dropped and your parent sees a gap. Turn it off anyway?'**
  String get bufferOffWarning;

  /// No description provided for @changePasswordButton.
  ///
  /// In en, this message translates to:
  /// **'Change password'**
  String get changePasswordButton;

  /// No description provided for @changePasswordHint.
  ///
  /// In en, this message translates to:
  /// **'Then set a new one'**
  String get changePasswordHint;

  /// No description provided for @checkExactAlarmNa.
  ///
  /// In en, this message translates to:
  /// **'Not needed on this Android version'**
  String get checkExactAlarmNa;

  /// No description provided for @checklistAllDone.
  ///
  /// In en, this message translates to:
  /// **'All done'**
  String get checklistAllDone;

  /// No description provided for @checklistAutoSection.
  ///
  /// In en, this message translates to:
  /// **'Checked automatically'**
  String get checklistAutoSection;

  /// No description provided for @checklistButton.
  ///
  /// In en, this message translates to:
  /// **'Reliability settings'**
  String get checklistButton;

  /// No description provided for @checklistDeviceLabel.
  ///
  /// In en, this message translates to:
  /// **'This phone: {device}'**
  String checklistDeviceLabel(String device);

  /// No description provided for @checklistIntroAuto.
  ///
  /// In en, this message translates to:
  /// **'Everything is checked automatically, nothing to tick by hand. The page re-reads when you come back; long-press a row to copy a question for an AI'**
  String get checklistIntroAuto;

  /// No description provided for @checklistNotNeededSection.
  ///
  /// In en, this message translates to:
  /// **'Not needed on this phone ({count})'**
  String checklistNotNeededSection(int count);

  /// No description provided for @checklistPending.
  ///
  /// In en, this message translates to:
  /// **'{count} still being verified'**
  String checklistPending(int count);

  /// No description provided for @checklistRemaining.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 item left} other{{count} items left}}'**
  String checklistRemaining(int count);

  /// No description provided for @checklistSuggestSection.
  ///
  /// In en, this message translates to:
  /// **'Suggested (doesn\'t block tracking)'**
  String get checklistSuggestSection;

  /// No description provided for @checklistTitle.
  ///
  /// In en, this message translates to:
  /// **'Settings for reliable tracking'**
  String get checklistTitle;

  /// No description provided for @checklistVendorNote.
  ///
  /// In en, this message translates to:
  /// **'The system can\'t report these. While tracking is on, the app judges them by whether it recovers after a reboot, reports on time with the screen locked, and can send with the screen locked'**
  String get checklistVendorNote;

  /// No description provided for @checklistVendorSection.
  ///
  /// In en, this message translates to:
  /// **'Phone maker settings (judged by behaviour)'**
  String get checklistVendorSection;

  /// No description provided for @confirmPasswordHint.
  ///
  /// In en, this message translates to:
  /// **'Same digits as before'**
  String get confirmPasswordHint;

  /// No description provided for @confirmPasswordTitle.
  ///
  /// In en, this message translates to:
  /// **'Enter it again'**
  String get confirmPasswordTitle;

  /// No description provided for @copiedMessage.
  ///
  /// In en, this message translates to:
  /// **'Copied. Paste it into an AI assistant.'**
  String get copiedMessage;

  /// No description provided for @copyButton.
  ///
  /// In en, this message translates to:
  /// **'Copy'**
  String get copyButton;

  /// No description provided for @currentPasswordTitle.
  ///
  /// In en, this message translates to:
  /// **'Enter the current password'**
  String get currentPasswordTitle;

  /// No description provided for @deleteKey.
  ///
  /// In en, this message translates to:
  /// **'Delete'**
  String get deleteKey;

  /// No description provided for @disablePasswordButton.
  ///
  /// In en, this message translates to:
  /// **'Turn off password'**
  String get disablePasswordButton;

  /// No description provided for @disablePasswordHint.
  ///
  /// In en, this message translates to:
  /// **'Enter the current password to confirm. The app will then open without one.'**
  String get disablePasswordHint;

  /// No description provided for @disablePasswordTitle.
  ///
  /// In en, this message translates to:
  /// **'Turn off the app password'**
  String get disablePasswordTitle;

  /// No description provided for @fallbackPath.
  ///
  /// In en, this message translates to:
  /// **'Could not open it directly, opened the nearest settings page instead. Look for: {path}'**
  String fallbackPath(String path);

  /// No description provided for @goToEnableButton.
  ///
  /// In en, this message translates to:
  /// **'Turn on'**
  String get goToEnableButton;

  /// No description provided for @goToSettingsButton.
  ///
  /// In en, this message translates to:
  /// **'Open settings'**
  String get goToSettingsButton;

  /// No description provided for @guardAdminNotActivated.
  ///
  /// In en, this message translates to:
  /// **'Uninstall protection was not activated.'**
  String get guardAdminNotActivated;

  /// No description provided for @guardAdminTitle.
  ///
  /// In en, this message translates to:
  /// **'Uninstall protection (device admin)'**
  String get guardAdminTitle;

  /// No description provided for @guardAdminWhy.
  ///
  /// In en, this message translates to:
  /// **'The child cannot uninstall this app until it is turned off here with the password.'**
  String get guardAdminWhy;

  /// No description provided for @guardLost.
  ///
  /// In en, this message translates to:
  /// **'It was turned off; turn it on again (an alarm is sent to the parent when this happens).'**
  String get guardLost;

  /// No description provided for @guardNeedPassword.
  ///
  /// In en, this message translates to:
  /// **'Set an app password first: it is needed to turn the protection off.'**
  String get guardNeedPassword;

  /// No description provided for @guardSection.
  ///
  /// In en, this message translates to:
  /// **'Protection'**
  String get guardSection;

  /// No description provided for @idCopiedMessage.
  ///
  /// In en, this message translates to:
  /// **'Device identifier copied'**
  String get idCopiedMessage;

  /// No description provided for @itemAssociatedLaunch.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Secondary launch}}'**
  String itemAssociatedLaunch(String brand);

  /// No description provided for @itemAssociatedLaunchPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App launch → this app → Secondary launch}}'**
  String itemAssociatedLaunchPath(String brand);

  /// No description provided for @itemAssociatedLaunchWhy.
  ///
  /// In en, this message translates to:
  /// **'Lets other system components wake the app, so it recovers faster.'**
  String get itemAssociatedLaunchWhy;

  /// No description provided for @itemAutostart.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, huawei{Auto-launch} honor{Auto-launch} other{Autostart}}'**
  String itemAutostart(String brand);

  /// No description provided for @itemAutostartPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, huawei{Phone Manager → App launch → this app → turn off Manage automatically → Auto-launch} honor{Phone Manager → App launch → this app → turn off Manage automatically → Auto-launch} other{Look for Autostart in app info or the security app; if your system has none, just tick it.}}'**
  String itemAutostartPath(String brand);

  /// No description provided for @itemAutostartWhy.
  ///
  /// In en, this message translates to:
  /// **'Lets the app start itself again after it is stopped or the phone restarts.'**
  String get itemAutostartWhy;

  /// No description provided for @itemBackgroundActivity.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Run in background}}'**
  String itemBackgroundActivity(String brand);

  /// No description provided for @itemBackgroundActivityPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App launch → this app → Run in background}}'**
  String itemBackgroundActivityPath(String brand);

  /// No description provided for @itemBackgroundActivityWhy.
  ///
  /// In en, this message translates to:
  /// **'Keeps reporting after the screen locks.'**
  String get itemBackgroundActivityWhy;

  /// No description provided for @itemBackgroundLocation.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Allow all the time}}'**
  String itemBackgroundLocation(String brand);

  /// No description provided for @itemBackgroundLocationPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App info → Permissions → Location → Allow all the time}}'**
  String itemBackgroundLocationPath(String brand);

  /// No description provided for @itemBackgroundLocationWhy.
  ///
  /// In en, this message translates to:
  /// **'Otherwise the app gets no location once it is in the background.'**
  String get itemBackgroundLocationWhy;

  /// No description provided for @itemBackgroundRestriction.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Not \"Restricted\"}}'**
  String itemBackgroundRestriction(String brand);

  /// No description provided for @itemBackgroundRestrictionPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App info → App battery usage → do not choose Restricted}}'**
  String itemBackgroundRestrictionPath(String brand);

  /// No description provided for @itemBackgroundRestrictionWhy.
  ///
  /// In en, this message translates to:
  /// **'When restricted, the app cannot run in the background at all.'**
  String get itemBackgroundRestrictionWhy;

  /// No description provided for @itemBatteryOptimization.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, samsung{Unrestricted} other{Unrestricted}}'**
  String itemBatteryOptimization(String brand);

  /// No description provided for @itemBatteryOptimizationPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App info → App battery usage → Unrestricted}}'**
  String itemBatteryOptimizationPath(String brand);

  /// No description provided for @itemBatteryOptimizationWhy.
  ///
  /// In en, this message translates to:
  /// **'Battery optimization pauses the app after the screen locks.'**
  String get itemBatteryOptimizationWhy;

  /// No description provided for @itemDataSaver.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Data saver: unrestricted for this app}}'**
  String itemDataSaver(String brand);

  /// No description provided for @itemDataSaverPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Settings → Network → Data saver → Unrestricted data → turn on this app}}'**
  String itemDataSaverPath(String brand);

  /// No description provided for @itemDataSaverWhy.
  ///
  /// In en, this message translates to:
  /// **'With data saver on, reports may not get through.'**
  String get itemDataSaverWhy;

  /// No description provided for @itemExactAlarm.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Alarms & reminders}}'**
  String itemExactAlarm(String brand);

  /// No description provided for @itemExactAlarmPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App info → Alarms & reminders}}'**
  String itemExactAlarmPath(String brand);

  /// No description provided for @itemExactAlarmWhy.
  ///
  /// In en, this message translates to:
  /// **'Lets heartbeats fire on time.'**
  String get itemExactAlarmWhy;

  /// No description provided for @itemHibernation.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Pause app activity if unused: off}}'**
  String itemHibernation(String brand);

  /// No description provided for @itemHibernationPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App info → Pause app activity if unused → off}}'**
  String itemHibernationPath(String brand);

  /// No description provided for @itemHibernationWhy.
  ///
  /// In en, this message translates to:
  /// **'If on, the app is paused and loses its permissions when not opened for months.'**
  String get itemHibernationWhy;

  /// No description provided for @itemLocationService.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Use location}}'**
  String itemLocationService(String brand);

  /// No description provided for @itemLocationServicePath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Settings → Location → Use location}}'**
  String itemLocationServicePath(String brand);

  /// No description provided for @itemLocationServiceWhy.
  ///
  /// In en, this message translates to:
  /// **'The system-wide location switch; when off, no app can get a location.'**
  String get itemLocationServiceWhy;

  /// No description provided for @itemMotionPermission.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Allow physical activity}}'**
  String itemMotionPermission(String brand);

  /// No description provided for @itemMotionPermissionPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App info → Permissions → Physical activity → Allow}}'**
  String itemMotionPermissionPath(String brand);

  /// No description provided for @itemMotionPermissionWhy.
  ///
  /// In en, this message translates to:
  /// **'Without it walking and riding are noticed minutes later.'**
  String get itemMotionPermissionWhy;

  /// No description provided for @itemNetworkAccess.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Network access: mobile data and Wi-Fi allowed}}'**
  String itemNetworkAccess(String brand);

  /// No description provided for @itemNetworkAccessPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, huawei{Phone Manager → Data usage → Network access → this app → allow Mobile data and Wi-Fi} honor{Phone Manager → Data usage → Network access → this app → allow Mobile data and Wi-Fi} xiaomi{App info → Restrict data usage → allow both} vivo{Search Settings for \"Network access\" → this app → allow both} other{Search Settings for \"network\" → this app → allow Mobile data and Wi-Fi}}'**
  String itemNetworkAccessPath(String brand);

  /// No description provided for @itemNetworkAccessWhy.
  ///
  /// In en, this message translates to:
  /// **'Without it the app can locate but not send.'**
  String get itemNetworkAccessWhy;

  /// No description provided for @itemNotifications.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Allow notifications}}'**
  String itemNotifications(String brand);

  /// No description provided for @itemNotificationsPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App info → Notifications → Allow notifications}}'**
  String itemNotificationsPath(String brand);

  /// No description provided for @itemNotificationsWhy.
  ///
  /// In en, this message translates to:
  /// **'Without the tracking notification the service is killed more easily.'**
  String get itemNotificationsWhy;

  /// No description provided for @itemPowerSave.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Battery saver: off}}'**
  String itemPowerSave(String brand);

  /// No description provided for @itemPowerSavePath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Settings → Battery → turn off Battery saver}}'**
  String itemPowerSavePath(String brand);

  /// No description provided for @itemPowerSaveWhy.
  ///
  /// In en, this message translates to:
  /// **'Turn battery saver off, and don\'t let it turn on automatically at low battery.'**
  String get itemPowerSaveWhy;

  /// No description provided for @itemPowerSaving.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, xiaomi{Battery saver: No restrictions} samsung{Never sleeping apps} vivo{Allow high background power consumption} other{Allow background activity}}'**
  String itemPowerSaving(String brand);

  /// No description provided for @itemPowerSavingPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, xiaomi{App info → Battery saver → No restrictions} samsung{Settings → Battery → Background usage limits → Never sleeping apps → add this app} vivo{Settings → Battery → Background power consumption management → this app → Allow} other{App info → Battery usage → Allow background activity}}'**
  String itemPowerSavingPath(String brand);

  /// No description provided for @itemPowerSavingWhy.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{The manufacturer\'s own power saving stops background apps separately from Android\'s battery optimization.}}'**
  String itemPowerSavingWhy(String brand);

  /// No description provided for @itemPreciseLocation.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Use precise location}}'**
  String itemPreciseLocation(String brand);

  /// No description provided for @itemPreciseLocationPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{App info → Permissions → Location → Use precise location}}'**
  String itemPreciseLocationPath(String brand);

  /// No description provided for @itemPreciseLocationWhy.
  ///
  /// In en, this message translates to:
  /// **'With approximate location only, the position can be off by kilometres.'**
  String get itemPreciseLocationWhy;

  /// No description provided for @itemRecentsLock.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Lock}}'**
  String itemRecentsLock(String brand);

  /// No description provided for @itemRecentsLockPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{While this app is open, open recent apps and lock its card (long-press or pull it down, choose Lock). Once you leave the app its card is hidden there.}}'**
  String itemRecentsLockPath(String brand);

  /// No description provided for @itemRecentsLockWhy.
  ///
  /// In en, this message translates to:
  /// **'\"Clear all\" in recent apps will not close it.'**
  String get itemRecentsLockWhy;

  /// No description provided for @itemScreenOffNetwork.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, huawei{Stay connected during sleep} honor{Stay connected during sleep} xiaomi{Turn off mobile data when locked: never} other{Sleep standby optimization: off}}'**
  String itemScreenOffNetwork(String brand);

  /// No description provided for @itemScreenOffNetworkPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, huawei{Settings → Battery → More battery settings → turn on \"Stay connected during sleep\"} honor{Settings → Battery → More battery settings → turn on \"Stay connected during sleep\"} xiaomi{Settings → Battery → More settings → \"Turn off mobile data when device is locked\" → Never} oppo{Settings → Battery → More settings → turn off \"Sleep standby optimization\"} vivo{Settings → Battery → turn off \"Sleep standby optimization\"} other{Search Settings for \"sleep\" and keep the network on when locked}}'**
  String itemScreenOffNetworkPath(String brand);

  /// No description provided for @itemScreenOffNetworkWhy.
  ///
  /// In en, this message translates to:
  /// **'Otherwise the network drops soon after locking.'**
  String get itemScreenOffNetworkWhy;

  /// No description provided for @itemWifiScan.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Wi-Fi scanning}}'**
  String itemWifiScan(String brand);

  /// No description provided for @itemWifiScanPath.
  ///
  /// In en, this message translates to:
  /// **'{brand, select, other{Search Settings for \"Wi-Fi scanning\" and turn it on}}'**
  String itemWifiScanPath(String brand);

  /// No description provided for @itemWifiScanWhy.
  ///
  /// In en, this message translates to:
  /// **'Without it, indoor locations can be off by hundreds of meters or more.'**
  String get itemWifiScanWhy;

  /// No description provided for @lastReportLabel.
  ///
  /// In en, this message translates to:
  /// **'Last successful report'**
  String get lastReportLabel;

  /// No description provided for @lastReportNever.
  ///
  /// In en, this message translates to:
  /// **'No successful report yet'**
  String get lastReportNever;

  /// No description provided for @locationFailedMessage.
  ///
  /// In en, this message translates to:
  /// **'Location not sent: maybe no network or no fix yet. Try again in a moment.'**
  String get locationFailedMessage;

  /// No description provided for @locationSentMessage.
  ///
  /// In en, this message translates to:
  /// **'Location sent to your parent'**
  String get locationSentMessage;

  /// No description provided for @locationSourceAutoLabel.
  ///
  /// In en, this message translates to:
  /// **'Auto (prefer AMap)'**
  String get locationSourceAutoLabel;

  /// No description provided for @locationSourceHint.
  ///
  /// In en, this message translates to:
  /// **'System only: AMap is not used, indoor fixes often fail'**
  String get locationSourceHint;

  /// No description provided for @locationSourceLabel.
  ///
  /// In en, this message translates to:
  /// **'Location source'**
  String get locationSourceLabel;

  /// No description provided for @locationSourceSystemLabel.
  ///
  /// In en, this message translates to:
  /// **'System location only'**
  String get locationSourceSystemLabel;

  /// No description provided for @lockHint.
  ///
  /// In en, this message translates to:
  /// **'This is the app\'s own password, not the phone\'s screen lock'**
  String get lockHint;

  /// No description provided for @lockTitle.
  ///
  /// In en, this message translates to:
  /// **'Enter the app password'**
  String get lockTitle;

  /// No description provided for @newPasswordTitle.
  ///
  /// In en, this message translates to:
  /// **'Enter a new password'**
  String get newPasswordTitle;

  /// No description provided for @notApplicableMessage.
  ///
  /// In en, this message translates to:
  /// **'Not available on this Android version'**
  String get notApplicableMessage;

  /// No description provided for @passwordChanged.
  ///
  /// In en, this message translates to:
  /// **'Password changed'**
  String get passwordChanged;

  /// No description provided for @passwordDisabled.
  ///
  /// In en, this message translates to:
  /// **'App password turned off'**
  String get passwordDisabled;

  /// No description provided for @passwordItem.
  ///
  /// In en, this message translates to:
  /// **'App password'**
  String get passwordItem;

  /// No description provided for @passwordLockedOut.
  ///
  /// In en, this message translates to:
  /// **'{count} wrong tries in a row. The app will close.'**
  String passwordLockedOut(int count);

  /// No description provided for @passwordMismatch.
  ///
  /// In en, this message translates to:
  /// **'They didn\'t match. Start again.'**
  String get passwordMismatch;

  /// No description provided for @passwordNotSet.
  ///
  /// In en, this message translates to:
  /// **'Not set'**
  String get passwordNotSet;

  /// No description provided for @passwordRemaining.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 try left} other{{count} tries left}}'**
  String passwordRemaining(int count);

  /// No description provided for @passwordWhy.
  ///
  /// In en, this message translates to:
  /// **'Once set, the app asks for it every time it is opened, so the child cannot turn off tracking or change settings. This is the app\'s own password, unrelated to the phone\'s screen lock.'**
  String get passwordWhy;

  /// No description provided for @passwordWrongRemaining.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{Wrong password, 1 try left} other{Wrong password, {count} tries left}}'**
  String passwordWrongRemaining(int count);

  /// No description provided for @pendingActionConfig.
  ///
  /// In en, this message translates to:
  /// **'Change server and device settings'**
  String get pendingActionConfig;

  /// No description provided for @pendingActionGeneric.
  ///
  /// In en, this message translates to:
  /// **'The previous action needs the password. Continue now?'**
  String get pendingActionGeneric;

  /// No description provided for @pendingActionStart.
  ///
  /// In en, this message translates to:
  /// **'Turn tracking on'**
  String get pendingActionStart;

  /// No description provided for @pendingActionStop.
  ///
  /// In en, this message translates to:
  /// **'Stop tracking (the parent will be notified)'**
  String get pendingActionStop;

  /// No description provided for @pendingActionTitle.
  ///
  /// In en, this message translates to:
  /// **'Continue?'**
  String get pendingActionTitle;

  /// No description provided for @promptFirstRun.
  ///
  /// In en, this message translates to:
  /// **'First time here: set up the settings needed for reliable tracking, or the system may stop the app in the background.'**
  String get promptFirstRun;

  /// No description provided for @promptIncomplete.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 setting for reliable tracking is not done} other{{count} settings for reliable tracking are not done}}; the system may stop the app in the background.'**
  String promptIncomplete(int count);

  /// No description provided for @promptNoPassword.
  ///
  /// In en, this message translates to:
  /// **'No app password: the child can turn off tracking'**
  String get promptNoPassword;

  /// No description provided for @promptOpenButton.
  ///
  /// In en, this message translates to:
  /// **'Open checklist'**
  String get promptOpenButton;

  /// No description provided for @promptSetPasswordButton.
  ///
  /// In en, this message translates to:
  /// **'Set password'**
  String get promptSetPasswordButton;

  /// No description provided for @promptSystemUpdated.
  ///
  /// In en, this message translates to:
  /// **'The system was updated and may have reset some settings. Open the checklist and confirm again.'**
  String get promptSystemUpdated;

  /// No description provided for @recorderClearButton.
  ///
  /// In en, this message translates to:
  /// **'Delete old'**
  String get recorderClearButton;

  /// No description provided for @recorderExportTitle.
  ///
  /// In en, this message translates to:
  /// **'Export recordings'**
  String get recorderExportTitle;

  /// No description provided for @recorderOff.
  ///
  /// In en, this message translates to:
  /// **'Records sensors, Wi-Fi and location every 2 seconds, kept on this phone only. {files} files, {mb} MB'**
  String recorderOff(int files, String mb);

  /// No description provided for @recorderOn.
  ///
  /// In en, this message translates to:
  /// **'Recording: {rows} rows this time, {files} files, {mb} MB. Uses more battery, turn off when done'**
  String recorderOn(int rows, int files, String mb);

  /// No description provided for @recorderRefreshButton.
  ///
  /// In en, this message translates to:
  /// **'Refresh'**
  String get recorderRefreshButton;

  /// No description provided for @recorderShareButton.
  ///
  /// In en, this message translates to:
  /// **'Share'**
  String get recorderShareButton;

  /// No description provided for @recorderStopped.
  ///
  /// In en, this message translates to:
  /// **'Recording stopped: {reason}'**
  String recorderStopped(String reason);

  /// No description provided for @recorderTitle.
  ///
  /// In en, this message translates to:
  /// **'Sensor recording (testing)'**
  String get recorderTitle;

  /// No description provided for @reportOverdueMessage.
  ///
  /// In en, this message translates to:
  /// **'Nothing reported for over 3 heartbeat periods. Check the reliability settings.'**
  String get reportOverdueMessage;

  /// No description provided for @scanConfigTooltip.
  ///
  /// In en, this message translates to:
  /// **'Scan configuration'**
  String get scanConfigTooltip;

  /// No description provided for @sendNoPermissionMessage.
  ///
  /// In en, this message translates to:
  /// **'Can\'t send without location permission. Allow it in system settings.'**
  String get sendNoPermissionMessage;

  /// No description provided for @setPasswordButton.
  ///
  /// In en, this message translates to:
  /// **'Set'**
  String get setPasswordButton;

  /// No description provided for @setPasswordHint.
  ///
  /// In en, this message translates to:
  /// **'{min} to {max} digits'**
  String setPasswordHint(int min, int max);

  /// No description provided for @setPasswordTitle.
  ///
  /// In en, this message translates to:
  /// **'Set the app password'**
  String get setPasswordTitle;

  /// No description provided for @startFailedMessage.
  ///
  /// In en, this message translates to:
  /// **'Failed to start tracking. Check location permissions.'**
  String get startFailedMessage;

  /// No description provided for @techChangeConfirmButton.
  ///
  /// In en, this message translates to:
  /// **'Turn off anyway'**
  String get techChangeConfirmButton;

  /// No description provided for @techChangeTitle.
  ///
  /// In en, this message translates to:
  /// **'This affects reliability'**
  String get techChangeTitle;

  /// No description provided for @techOptionsTitle.
  ///
  /// In en, this message translates to:
  /// **'Technical options (usually leave alone)'**
  String get techOptionsTitle;

  /// No description provided for @trackingBlocked.
  ///
  /// In en, this message translates to:
  /// **'{count, plural, =1{1 setting is not done yet} other{{count} settings are not done yet}}. Tick every item in the checklist before turning on tracking.'**
  String trackingBlocked(int count);

  /// No description provided for @updateAllowInstall.
  ///
  /// In en, this message translates to:
  /// **'Allow this app to install updates, then come back to install'**
  String get updateAllowInstall;

  /// No description provided for @updateAvailable.
  ///
  /// In en, this message translates to:
  /// **'New version available, tap to update'**
  String get updateAvailable;

  /// No description provided for @updateChecksumFailed.
  ///
  /// In en, this message translates to:
  /// **'The downloaded file doesn\'t match, not installing'**
  String get updateChecksumFailed;

  /// No description provided for @updateDownloadFailed.
  ///
  /// In en, this message translates to:
  /// **'Download failed. Check the network and tap again'**
  String get updateDownloadFailed;

  /// No description provided for @updateDownloading.
  ///
  /// In en, this message translates to:
  /// **'Downloading new version'**
  String get updateDownloading;

  /// No description provided for @updateIdentityFailed.
  ///
  /// In en, this message translates to:
  /// **'The downloaded file is not an official build of this app, not installed'**
  String get updateIdentityFailed;

  /// No description provided for @updateInstallFailed.
  ///
  /// In en, this message translates to:
  /// **'Couldn\'t open the installer'**
  String get updateInstallFailed;

  /// No description provided for @updateReady.
  ///
  /// In en, this message translates to:
  /// **'Downloaded, tap to install'**
  String get updateReady;

  /// No description provided for @vendorBadBreath.
  ///
  /// In en, this message translates to:
  /// **'Not working: reports are late with the screen locked'**
  String get vendorBadBreath;

  /// No description provided for @vendorBadBreathMinutes.
  ///
  /// In en, this message translates to:
  /// **'Not working: reports were {minutes} min late with the screen locked'**
  String vendorBadBreathMinutes(int minutes);

  /// No description provided for @vendorBadReboot.
  ///
  /// In en, this message translates to:
  /// **'Not working: did not resume after a reboot until the app was opened'**
  String get vendorBadReboot;

  /// No description provided for @vendorBadSend.
  ///
  /// In en, this message translates to:
  /// **'Not working: can\'t send with the screen locked, only after it turns on'**
  String get vendorBadSend;

  /// No description provided for @vendorOkBreath.
  ///
  /// In en, this message translates to:
  /// **'Working: reports on time with the screen locked'**
  String get vendorOkBreath;

  /// No description provided for @vendorOkReboot.
  ///
  /// In en, this message translates to:
  /// **'Working: resumed by itself after a reboot'**
  String get vendorOkReboot;

  /// No description provided for @vendorOkSend.
  ///
  /// In en, this message translates to:
  /// **'Working: can send with the screen locked'**
  String get vendorOkSend;

  /// No description provided for @vendorPending.
  ///
  /// In en, this message translates to:
  /// **'Not verified yet: '**
  String get vendorPending;

  /// No description provided for @vendorPendingBreath.
  ///
  /// In en, this message translates to:
  /// **'leave the phone locked for 15+ minutes with tracking on, then check back'**
  String get vendorPendingBreath;

  /// No description provided for @vendorPendingReboot.
  ///
  /// In en, this message translates to:
  /// **'restart the phone with tracking on, don\'t open the app, check back in a few minutes'**
  String get vendorPendingReboot;

  /// No description provided for @vendorPendingSend.
  ///
  /// In en, this message translates to:
  /// **'leave the phone locked for a while with tracking on, then check back'**
  String get vendorPendingSend;

  /// No description provided for @vendorRecheckButton.
  ///
  /// In en, this message translates to:
  /// **'Fixed it'**
  String get vendorRecheckButton;

  /// No description provided for @verifyHint.
  ///
  /// In en, this message translates to:
  /// **'A parent needs to enter it to continue'**
  String get verifyHint;

  /// No description provided for @wakelockHint.
  ///
  /// In en, this message translates to:
  /// **'Usually off. On: keeps the phone awake when locked, uses more battery'**
  String get wakelockHint;

  /// No description provided for @faultSection.
  ///
  /// In en, this message translates to:
  /// **'Run log'**
  String get faultSection;

  /// No description provided for @faultNote.
  ///
  /// In en, this message translates to:
  /// **'The latest problem the app ran into while reporting and how it handled it'**
  String get faultNote;

  /// No description provided for @faultNone.
  ///
  /// In en, this message translates to:
  /// **'No problems recorded'**
  String get faultNone;

  /// No description provided for @faultRescue.
  ///
  /// In en, this message translates to:
  /// **'Reporting got stuck and the app restarted location or tracking'**
  String get faultRescue;

  /// No description provided for @faultDegraded.
  ///
  /// In en, this message translates to:
  /// **'AMap location failed; system location is used for this session'**
  String get faultDegraded;

  /// No description provided for @faultStorage.
  ///
  /// In en, this message translates to:
  /// **'Storage couldn\'t be written (maybe full); points are kept in memory and still sent'**
  String get faultStorage;

  /// No description provided for @faultDropped.
  ///
  /// In en, this message translates to:
  /// **'Too many unsent records; the oldest were dropped'**
  String get faultDropped;

  /// No description provided for @faultCrash.
  ///
  /// In en, this message translates to:
  /// **'A background error happened and was recovered'**
  String get faultCrash;
}

class _AppLocalizationsDelegate
    extends LocalizationsDelegate<AppLocalizations> {
  const _AppLocalizationsDelegate();

  @override
  Future<AppLocalizations> load(Locale locale) {
    return SynchronousFuture<AppLocalizations>(lookupAppLocalizations(locale));
  }

  @override
  bool isSupported(Locale locale) =>
      <String>['en', 'zh'].contains(locale.languageCode);

  @override
  bool shouldReload(_AppLocalizationsDelegate old) => false;
}

AppLocalizations lookupAppLocalizations(Locale locale) {
  // Lookup logic when only language code is specified.
  switch (locale.languageCode) {
    case 'en':
      return AppLocalizationsEn();
    case 'zh':
      return AppLocalizationsZh();
  }

  throw FlutterError(
    'AppLocalizations.delegate failed to load unsupported locale "$locale". This is likely '
    'an issue with the localizations generation tool. Please file an issue '
    'on GitHub with a reproducible sample app and the gen-l10n configuration '
    'that was used.',
  );
}
