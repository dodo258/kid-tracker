import 'package:traccar_client_sdk/traccar_client_sdk.dart';

import '../l10n/app_localizations.dart';
import 'background_check.dart';

/// 清单一行的文字：项目名（用该品牌系统里的原话）、为什么要开、找不到页面时的菜单路径。
typedef EntryTexts = ({String title, String why, String path});

EntryTexts autoTexts(AppLocalizations l, CheckItem item, String brand) => switch (item) {
      CheckItem.locationService =>
        (title: l.itemLocationService(brand), why: l.itemLocationServiceWhy, path: l.itemLocationServicePath(brand)),
      CheckItem.backgroundLocation => (
          title: l.itemBackgroundLocation(brand),
          why: l.itemBackgroundLocationWhy,
          path: l.itemBackgroundLocationPath(brand),
        ),
      CheckItem.preciseLocation =>
        (title: l.itemPreciseLocation(brand), why: l.itemPreciseLocationWhy, path: l.itemPreciseLocationPath(brand)),
      CheckItem.motionPermission => (
          title: l.itemMotionPermission(brand),
          why: l.itemMotionPermissionWhy,
          path: l.itemMotionPermissionPath(brand),
        ),
      CheckItem.notifications =>
        (title: l.itemNotifications(brand), why: l.itemNotificationsWhy, path: l.itemNotificationsPath(brand)),
      CheckItem.batteryOptimization => (
          title: l.itemBatteryOptimization(brand),
          why: l.itemBatteryOptimizationWhy,
          path: l.itemBatteryOptimizationPath(brand),
        ),
      CheckItem.backgroundRestriction => (
          title: l.itemBackgroundRestriction(brand),
          why: l.itemBackgroundRestrictionWhy,
          path: l.itemBackgroundRestrictionPath(brand),
        ),
      CheckItem.hibernation =>
        (title: l.itemHibernation(brand), why: l.itemHibernationWhy, path: l.itemHibernationPath(brand)),
      CheckItem.exactAlarm =>
        (title: l.itemExactAlarm(brand), why: l.itemExactAlarmWhy, path: l.itemExactAlarmPath(brand)),
      CheckItem.powerSave => (title: l.itemPowerSave(brand), why: l.itemPowerSaveWhy, path: l.itemPowerSavePath(brand)),
      CheckItem.wifiScan => (title: l.itemWifiScan(brand), why: l.itemWifiScanWhy, path: l.itemWifiScanPath(brand)),
      CheckItem.dataSaver => (title: l.itemDataSaver(brand), why: l.itemDataSaverWhy, path: l.itemDataSaverPath(brand)),
    };

EntryTexts vendorTexts(AppLocalizations l, VendorItem item, String brand) => switch (item) {
      VendorItem.autostart => (title: l.itemAutostart(brand), why: l.itemAutostartWhy, path: l.itemAutostartPath(brand)),
      VendorItem.associatedLaunch => (
          title: l.itemAssociatedLaunch(brand),
          why: l.itemAssociatedLaunchWhy,
          path: l.itemAssociatedLaunchPath(brand),
        ),
      VendorItem.backgroundActivity => (
          title: l.itemBackgroundActivity(brand),
          why: l.itemBackgroundActivityWhy,
          path: l.itemBackgroundActivityPath(brand),
        ),
      VendorItem.powerSaving =>
        (title: l.itemPowerSaving(brand), why: l.itemPowerSavingWhy(brand), path: l.itemPowerSavingPath(brand)),
      VendorItem.networkAccess => (
          title: l.itemNetworkAccess(brand),
          why: l.itemNetworkAccessWhy,
          path: l.itemNetworkAccessPath(brand),
        ),
      VendorItem.screenOffNetwork => (
          title: l.itemScreenOffNetwork(brand),
          why: l.itemScreenOffNetworkWhy,
          path: l.itemScreenOffNetworkPath(brand),
        ),
      VendorItem.recentsLock =>
        (title: l.itemRecentsLock(brand), why: l.itemRecentsLockWhy, path: l.itemRecentsLockPath(brand)),
    };

/// 最近一次异常的中文说明（SDK 的 FaultKind）。
String faultText(AppLocalizations l, FaultKind kind) => switch (kind) {
      FaultKind.rescue => l.faultRescue,
      FaultKind.degraded => l.faultDegraded,
      FaultKind.storage => l.faultStorage,
      FaultKind.dropped => l.faultDropped,
      FaultKind.crash => l.faultCrash,
    };
