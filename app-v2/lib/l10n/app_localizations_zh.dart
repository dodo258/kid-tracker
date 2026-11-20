// ignore: unused_import
import 'package:intl/intl.dart' as intl;
import 'app_localizations.dart';

// ignore_for_file: type=lint

/// The translations for Chinese (`zh`).
class AppLocalizationsZh extends AppLocalizations {
  AppLocalizationsZh([String locale = 'zh']) : super(locale);

  @override
  String get trackingTitle => '追踪';

  @override
  String get settingsTitle => '设置';

  @override
  String get statusTitle => '日志';

  @override
  String get saveButton => '保存';

  @override
  String get cancelButton => '取消';

  @override
  String get okButton => '确认';

  @override
  String get locationButton => '发送位置';

  @override
  String get statusButton => '显示状态';

  @override
  String get settingsButton => '更改设置';

  @override
  String get invalidValue => '无效值';

  @override
  String get disabledValue => '已禁用';

  @override
  String get idLabel => '设备标识符';

  @override
  String get urlLabel => '服务器地址';

  @override
  String get accuracyLabel => '定位精度';

  @override
  String get highestAccuracyLabel => '最高';

  @override
  String get highAccuracyLabel => '高';

  @override
  String get mediumAccuracyLabel => '中';

  @override
  String get lowAccuracyLabel => '低';

  @override
  String get intervalLabel => '间隔（秒）';

  @override
  String get fastestIntervalLabel => '最短间隔（秒）';

  @override
  String get distanceLabel => '距离（米）';

  @override
  String get angleLabel => '角度（度）';

  @override
  String get heartbeatLabel => '静止时上报间隔（秒）';

  @override
  String get bufferLabel => '离线缓冲';

  @override
  String get wakelockLabel => '唤醒锁';

  @override
  String get stopDetectionLabel => '静止检测';

  @override
  String get preferPlatformProvidersLabel => '使用系统定位';

  @override
  String get trackingLabel => '持续追踪';

  @override
  String get advancedLabel => '高级设置';

  @override
  String get passwordLabel => '密码';

  @override
  String get optimizationMessage => '为确保追踪可靠，请禁用此应用的电池优化。';

  @override
  String get passwordError => '密码错误';

  @override
  String get startAction => '启动服务';

  @override
  String get stopAction => '停止服务';

  @override
  String get sosAction => '发送求救信号';

  @override
  String get disclosureMessage => '该应用会在后台收集位置和活动数据，并将其发送到配置的服务器。';

  @override
  String get configurationMessage => '应用新配置？';

  @override
  String get appTitle => '定位服务';

  @override
  String get notificationText => '服务中';

  @override
  String get sendingMessage => '正在发送…';

  @override
  String get sosSentMessage => '求救已发给家长';

  @override
  String get sosQueuedMessage => '求救还没发出去，联网后会自动补发。可以再发一次，或直接给家长打电话';

  @override
  String sendErrorMessage(String reason) {
    return '没发出去：$reason';
  }

  @override
  String get sosConfirmTitle => '发送求救？';

  @override
  String get sosConfirmMessage => '把位置和求救发给家长';

  @override
  String get sosConfirmButton => '发送求救';

  @override
  String get quickSosCloseButton => '关闭';

  @override
  String get quickSosRetryButton => '再发一次求救';

  @override
  String askAiPrompt(String device, String item) {
    return '我的手机是$device，怎么给 App「定位服务」开启「$item」';
  }

  @override
  String get bufferHint => '关了没网时位置会丢';

  @override
  String get bufferOffWarning => '关掉「离线缓冲」，没网时的位置会丢。确定关？';

  @override
  String get changePasswordButton => '修改密码';

  @override
  String get changePasswordHint => '验证后再设新密码';

  @override
  String get checkExactAlarmNa => '这个安卓版本不用单独开';

  @override
  String get checklistAllDone => '全部完成';

  @override
  String get checklistAutoSection => '自动检测';

  @override
  String get checklistButton => '稳定运行设置';

  @override
  String checklistDeviceLabel(String device) {
    return '本机：$device';
  }

  @override
  String get checklistIntroAuto => '都是自动检查的，不用手动打勾。回到这一页就重新读一遍；长按一行可复制去问 AI';

  @override
  String checklistNotNeededSection(int count) {
    return '本机不需要设置（$count 项）';
  }

  @override
  String checklistPending(int count) {
    return '还有 $count 项待验证';
  }

  @override
  String checklistRemaining(int count) {
    return '还差 $count 项';
  }

  @override
  String get checklistSuggestSection => '建议（不影响开追踪）';

  @override
  String get checklistTitle => '稳定运行需要的设置';

  @override
  String get checklistVendorNote =>
      '系统读不到这几项，App 开着追踪用一段时间，按重启后能不能自己恢复、锁屏时报得准不准、锁屏时发不发得出去自动判断';

  @override
  String get checklistVendorSection => '手机厂商的设置（看实际表现）';

  @override
  String get confirmPasswordHint => '和刚才输的一样';

  @override
  String get confirmPasswordTitle => '再输一次';

  @override
  String get copiedMessage => '已复制，可粘贴去问 AI';

  @override
  String get copyButton => '复制';

  @override
  String get currentPasswordTitle => '输入当前密码';

  @override
  String get deleteKey => '删除';

  @override
  String get disablePasswordButton => '关闭密码';

  @override
  String get disablePasswordHint => '关闭后打开 App 不用密码';

  @override
  String get disablePasswordTitle => '关闭 App 密码';

  @override
  String fallbackPath(String path) {
    return '没能直达，请找：$path';
  }

  @override
  String get goToEnableButton => '去开启';

  @override
  String get goToSettingsButton => '去设置';

  @override
  String get guardAdminNotActivated => '没有激活防卸载。';

  @override
  String get guardAdminTitle => '防卸载（设备管理员）';

  @override
  String get guardAdminWhy => '孩子卸载不了本 App';

  @override
  String get guardLost => '被关了，请重新开启';

  @override
  String get guardNeedPassword => '先设 App 密码';

  @override
  String get guardSection => '防护';

  @override
  String get idCopiedMessage => '设备标识符已复制';

  @override
  String itemAssociatedLaunch(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '允许关联启动'});
    return '$_temp0';
  }

  @override
  String itemAssociatedLaunchPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '设置 → 应用和服务 → 应用启动管理 → 定位服务 → 关闭「自动管理」→ 打开「允许关联启动」',
      'honor': '设置 → 应用 → 应用启动管理 → 定位服务 → 关闭「自动管理」→ 打开「允许关联启动」',
      'oppo': '设置 → 应用 → 关联启动 → 打开定位服务',
      'other': '设置 → 应用和服务 → 应用启动管理 → 定位服务 → 关闭「自动管理」→ 打开「允许关联启动」',
    });
    return '$_temp0';
  }

  @override
  String get itemAssociatedLaunchWhy => '被停后恢复更快';

  @override
  String itemAutostart(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '允许自启动',
      'honor': '允许自启动',
      'oppo': '允许自启动',
      'other': '自启动',
    });
    return '$_temp0';
  }

  @override
  String itemAutostartPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '设置 → 应用和服务 → 应用启动管理 → 定位服务 → 关闭「自动管理」→ 打开「允许自启动」',
      'honor': '设置 → 应用 → 应用启动管理 → 定位服务 → 关闭「自动管理」→ 打开「允许自启动」',
      'xiaomi': '应用详情 → 打开「自启动」（澎湃OS：应用详情 → 权限 → 自启动）',
      'oppo': '设置 → 应用 → 自启动 → 打开定位服务',
      'vivo': '设置搜「自启动」→ 打开定位服务（OriginOS 4：应用详情 → 权限 → 所有权限 → 自启动）',
      'other': '应用详情或手机管家里打开「自启动」；没有就直接打勾',
    });
    return '$_temp0';
  }

  @override
  String get itemAutostartWhy => '被停或重启后能自己恢复';

  @override
  String itemBackgroundActivity(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '允许后台活动'});
    return '$_temp0';
  }

  @override
  String itemBackgroundActivityPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '设置 → 应用和服务 → 应用启动管理 → 定位服务 → 关闭「自动管理」→ 打开「允许后台活动」',
      'honor': '设置 → 应用 → 应用启动管理 → 定位服务 → 关闭「自动管理」→ 打开「允许后台活动」',
      'other': '设置 → 应用和服务 → 应用启动管理 → 定位服务 → 关闭「自动管理」→ 打开「允许后台活动」',
    });
    return '$_temp0';
  }

  @override
  String get itemBackgroundActivityWhy => '锁屏后照常上报';

  @override
  String itemBackgroundLocation(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '始终允许'});
    return '$_temp0';
  }

  @override
  String itemBackgroundLocationPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': '应用详情 → 权限 → 位置信息 → 始终允许',
    });
    return '$_temp0';
  }

  @override
  String get itemBackgroundLocationWhy => '不然退到后台就没位置';

  @override
  String itemBackgroundRestriction(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'samsung': '后台运行不是「受限制」',
      'other': '电池用量不是「受限」',
    });
    return '$_temp0';
  }

  @override
  String itemBackgroundRestrictionPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'samsung': '应用详情 → 电池 → 后台运行 → 不受限制',
      'other': '应用详情 → 应用的电池用量 → 无限制',
    });
    return '$_temp0';
  }

  @override
  String get itemBackgroundRestrictionWhy => '受限就完全不能后台运行';

  @override
  String itemBatteryOptimization(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '电池优化「不允许」',
      'honor': '电池优化「不允许」',
      'samsung': '后台运行「不受限制」',
      'other': '电池用量「无限制」',
    });
    return '$_temp0';
  }

  @override
  String itemBatteryOptimizationPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '设置搜「电池优化」→「所有应用」→ 定位服务 → 不允许',
      'honor': '设置搜「电池优化」→「所有应用」→ 定位服务 → 不允许',
      'samsung': '应用详情 → 电池 → 后台运行 → 不受限制',
      'other': '应用详情 → 应用的电池用量 → 无限制（Android 15：先开「允许后台使用」）',
    });
    return '$_temp0';
  }

  @override
  String get itemBatteryOptimizationWhy => '不然锁屏一会儿就停';

  @override
  String itemDataSaver(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '省流量：本 App 不受限制'});
    return '$_temp0';
  }

  @override
  String itemDataSaverPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': '设置里搜「省流量」→ 不受限制的应用 → 打开定位服务',
    });
    return '$_temp0';
  }

  @override
  String get itemDataSaverWhy => '开着省流量可能发不出去';

  @override
  String itemExactAlarm(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '允许设置闹钟和提醒'});
    return '$_temp0';
  }

  @override
  String itemExactAlarmPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': '设置 → 应用 → 特殊应用权限 → 闹钟和提醒 → 定位服务',
    });
    return '$_temp0';
  }

  @override
  String get itemExactAlarmWhy => '按时报平安';

  @override
  String itemHibernation(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '暂停闲置应用的活动：关闭'});
    return '$_temp0';
  }

  @override
  String itemHibernationPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': '应用详情 → 关闭「暂停闲置应用的活动」（Android 13：「暂停未使用应用的活动」）',
    });
    return '$_temp0';
  }

  @override
  String get itemHibernationWhy => '不然久不打开会被收回权限';

  @override
  String itemLocationService(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'samsung': '定位服务',
      'other': '使用位置信息',
    });
    return '$_temp0';
  }

  @override
  String itemLocationServicePath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'samsung': '设置 → 定位服务 → 打开开关',
      'other': '设置 → 位置信息 → 打开「使用位置信息」',
    });
    return '$_temp0';
  }

  @override
  String get itemLocationServiceWhy => '系统定位总开关';

  @override
  String itemMotionPermission(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '允许身体活动（运动与健身）'});
    return '$_temp0';
  }

  @override
  String itemMotionPermissionPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': '应用详情 → 权限 → 身体活动 → 允许',
    });
    return '$_temp0';
  }

  @override
  String get itemMotionPermissionWhy => '不然认不出走路、坐车，出门后要晚几分钟才开始报位置';

  @override
  String itemNetworkAccess(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'xiaomi': '联网控制：都允许',
      'vivo': '联网管理：都允许',
      'other': '应用联网：移动数据和 WLAN 都允许',
    });
    return '$_temp0';
  }

  @override
  String itemNetworkAccessPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '手机管家 → 流量管理 → 应用联网 → 定位服务 → 打开「移动数据」和「WLAN」',
      'honor': '手机管家 → 流量管理 → 应用联网 → 定位服务 → 打开「移动数据」和「WLAN」',
      'xiaomi': '应用详情 → 联网控制 → 都打开（MIUI：安全中心 → 网络助手 → 联网控制）',
      'vivo': '设置里搜「联网管理」→ 定位服务 → 都允许（或 i管家 → 流量管理 → 联网管理）',
      'other': '设置里搜「联网」→ 定位服务 → 移动数据和 WLAN 都允许',
    });
    return '$_temp0';
  }

  @override
  String get itemNetworkAccessWhy => '关了能定位但发不出去';

  @override
  String itemNotifications(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '允许通知'});
    return '$_temp0';
  }

  @override
  String itemNotificationsPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': '应用详情 → 通知 → 打开「所有“定位服务”通知」',
    });
    return '$_temp0';
  }

  @override
  String get itemNotificationsWhy => '关了更容易被停掉';

  @override
  String itemPowerSave(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '省电模式：关闭'});
    return '$_temp0';
  }

  @override
  String itemPowerSavePath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': '设置 → 电池 → 关闭「省电模式」',
    });
    return '$_temp0';
  }

  @override
  String get itemPowerSaveWhy => '关掉省电模式，也别设成低电量自动开启';

  @override
  String itemPowerSaving(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'xiaomi': '省电策略「无限制」',
      'oppo': '允许完全后台行为',
      'vivo': '允许后台高耗电',
      'samsung': '从不休眠的应用程序',
      'other': '允许后台运行',
    });
    return '$_temp0';
  }

  @override
  String itemPowerSavingPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'xiaomi': '应用详情 → 省电策略 → 无限制（澎湃OS 2：应用详情 → 电量消耗 → 无限制）',
      'oppo': '设置 → 电池 → 更多设置 → 应用耗电管理 → 定位服务 → 打开「允许完全后台行为」',
      'vivo': '设置 → 电池 → 后台高耗电 → 定位服务 → 允许',
      'samsung': '设置 → 电池 → 后台使用限制 → 从不休眠的应用程序 → 添加定位服务',
      'other': '应用详情 → 电池 → 允许后台运行',
    });
    return '$_temp0';
  }

  @override
  String itemPowerSavingWhy(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '不放开也会在后台被停'});
    return '$_temp0';
  }

  @override
  String itemPreciseLocation(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '使用确切位置'});
    return '$_temp0';
  }

  @override
  String itemPreciseLocationPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'other': '应用详情 → 权限 → 位置信息 → 打开「使用确切位置」',
    });
    return '$_temp0';
  }

  @override
  String get itemPreciseLocationWhy => '不然误差几公里';

  @override
  String itemRecentsLock(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '多任务加锁',
      'honor': '多任务加锁',
      'samsung': '锁定此应用程序',
      'other': '锁定',
    });
    return '$_temp0';
  }

  @override
  String itemRecentsLockPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '别离开本 App，打开多任务，下拉它的卡片出现小锁',
      'honor': '别离开本 App，打开多任务，下拉它的卡片出现小锁',
      'xiaomi': '别离开本 App，打开多任务，长按卡片点「锁定」',
      'oppo': '别离开本 App，打开多任务，点卡片右上角更多 →「锁定」',
      'vivo': '别离开本 App，打开多任务，卡片轻轻下滑停顿，出现锁定标志',
      'samsung': '别离开本 App，打开多任务，点卡片上方图标 →「锁定此应用程序」或「保持打开」',
      'other': '别离开本 App，打开多任务，长按或下拉卡片选「锁定」；没有这功能就直接打勾',
    });
    return '$_temp0';
  }

  @override
  String get itemRecentsLockWhy => '一键清理时不被关掉';

  @override
  String itemScreenOffNetwork(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '休眠时始终保持网络连接',
      'honor': '休眠时始终保持网络连接',
      'xiaomi': '锁屏后关闭移动数据：从不',
      'other': '睡眠待机优化：关闭',
    });
    return '$_temp0';
  }

  @override
  String itemScreenOffNetworkPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {
      'huawei': '设置 → 电池 → 更多电池设置 → 打开「休眠时始终保持网络连接」',
      'honor': '设置 → 电池 → 更多电池设置 → 打开「休眠时始终保持网络连接」',
      'xiaomi': '设置 → 电池 → 更多设置 →「锁屏后关闭移动数据」选「从不」',
      'oppo': '设置 → 电池 → 更多设置 → 关闭「睡眠待机优化」（旧版：电池优化 → 高级优化）',
      'vivo': '设置 → 电池 → 关闭「睡眠待机优化」，选「均衡」或「正常」模式',
      'other': '设置里搜「休眠」或「锁屏断网」，让锁屏后不断网',
    });
    return '$_temp0';
  }

  @override
  String get itemScreenOffNetworkWhy => '不然锁屏一会儿就断网';

  @override
  String itemWifiScan(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': 'WLAN 扫描'});
    return '$_temp0';
  }

  @override
  String itemWifiScanPath(String brand) {
    String _temp0 = intl.Intl.selectLogic(brand, {'other': '设置里搜「WLAN 扫描」并打开'});
    return '$_temp0';
  }

  @override
  String get itemWifiScanWhy => '不然在室内误差几百米到几公里';

  @override
  String get lastReportLabel => '上次成功上报';

  @override
  String get lastReportNever => '还没有成功上报';

  @override
  String get locationFailedMessage => '位置没发出去，可能没网或定位不到';

  @override
  String get locationSentMessage => '位置已发给家长';

  @override
  String get locationSourceAutoLabel => '自动（优先高德）';

  @override
  String get locationSourceHint => '仅系统定位时室内常定位不到';

  @override
  String get locationSourceLabel => '定位来源';

  @override
  String get locationSourceSystemLabel => '仅系统定位';

  @override
  String get lockHint => '不是手机锁屏密码';

  @override
  String get lockTitle => '输入 App 密码';

  @override
  String get newPasswordTitle => '输入新密码';

  @override
  String get notApplicableMessage => '此系统版本没有这项';

  @override
  String get passwordChanged => '密码已修改';

  @override
  String get passwordDisabled => 'App 密码已关闭';

  @override
  String get passwordItem => 'App 密码';

  @override
  String passwordLockedOut(int count) {
    return '输错 $count 次，App 将退出';
  }

  @override
  String get passwordMismatch => '两次不一样，请重新设置';

  @override
  String get passwordNotSet => '未设置';

  @override
  String passwordRemaining(int count) {
    return '还能试 $count 次';
  }

  @override
  String get passwordWhy => '孩子没密码就关不了追踪';

  @override
  String passwordWrongRemaining(int count) {
    return '密码不对，还能试 $count 次';
  }

  @override
  String get pendingActionConfig => '修改服务器和设备设置';

  @override
  String get pendingActionGeneric => '刚才的操作要密码验证，现在继续吗？';

  @override
  String get pendingActionStart => '打开追踪';

  @override
  String get pendingActionStop => '停止追踪（家长会收到提醒）';

  @override
  String get pendingActionTitle => '要继续吗？';

  @override
  String get promptFirstRun => '第一次用：先弄好稳定运行设置';

  @override
  String promptIncomplete(int count) {
    return '还差 $count 项没弄好，后台可能被停';
  }

  @override
  String get promptNoPassword => '没设 App 密码，孩子能关追踪';

  @override
  String get promptOpenButton => '打开清单';

  @override
  String get promptSetPasswordButton => '设置密码';

  @override
  String get promptSystemUpdated => '系统更新了，请重新确认设置';

  @override
  String get recorderClearButton => '清掉旧的';

  @override
  String get recorderExportTitle => '导出录制文件';

  @override
  String recorderOff(int files, String mb) {
    return '每 2 秒记一次传感器、Wi-Fi 和定位，只存在本机。共 $files 个文件 $mb MB';
  }

  @override
  String recorderOn(int rows, int files, String mb) {
    return '录制中：这次 $rows 行，共 $files 个文件 $mb MB。比平时费电，测完关掉';
  }

  @override
  String get recorderRefreshButton => '刷新';

  @override
  String get recorderShareButton => '分享';

  @override
  String recorderStopped(String reason) {
    return '录制停了：$reason';
  }

  @override
  String get recorderTitle => '传感器录制（测试用）';

  @override
  String get reportOverdueMessage => '好久没报出去，请查稳定运行设置';

  @override
  String get scanConfigTooltip => '扫码配置';

  @override
  String get sendNoPermissionMessage => '没有定位权限，请在设置里允许';

  @override
  String get setPasswordButton => '设置';

  @override
  String setPasswordHint(int min, int max) {
    return '$min 到 $max 位数字';
  }

  @override
  String get setPasswordTitle => '设置 App 密码';

  @override
  String get startFailedMessage => '开不了追踪，请检查定位权限';

  @override
  String get techChangeConfirmButton => '仍然关掉';

  @override
  String get techChangeTitle => '改这个会影响稳定性';

  @override
  String get techOptionsTitle => '技术选项（不用动）';

  @override
  String trackingBlocked(int count) {
    return '还差 $count 项设置，全打勾才能开追踪';
  }

  @override
  String get updateAllowInstall => '请允许本应用安装更新，返回后自动安装';

  @override
  String get updateAvailable => '有新版本，点这里更新';

  @override
  String get updateChecksumFailed => '安装包校验不对，没有安装';

  @override
  String get updateDownloadFailed => '下载失败，检查网络后再点一次';

  @override
  String get updateDownloading => '正在下载新版本';

  @override
  String get updateIdentityFailed => '下载的安装包不是本 App 的正式版本，没有安装';

  @override
  String get updateInstallFailed => '没打开安装界面';

  @override
  String get updateReady => '下载好了，点这里安装';

  @override
  String get vendorBadBreath => '没生效：锁屏后报平安不按时';

  @override
  String vendorBadBreathMinutes(int minutes) {
    return '没生效：锁屏后报平安晚了 $minutes 分钟';
  }

  @override
  String get vendorBadReboot => '没生效：重启后没有自己接上，要打开 App 才恢复';

  @override
  String get vendorBadSend => '没生效：锁屏时发不出去，一亮屏才发出去';

  @override
  String get vendorOkBreath => '已生效：锁屏时按时报平安';

  @override
  String get vendorOkReboot => '已生效：重启后自己接上了';

  @override
  String get vendorOkSend => '已生效：锁屏时发得出去';

  @override
  String get vendorPending => '待验证：';

  @override
  String get vendorPendingBreath => '开着追踪、锁屏放 15 分钟以上再回来看';

  @override
  String get vendorPendingReboot => '开着追踪重启一次手机，开机后不用打开 App，几分钟后回来看';

  @override
  String get vendorPendingSend => '开着追踪、锁屏放一会儿再回来看';

  @override
  String get vendorRecheckButton => '改好了';

  @override
  String get verifyHint => '家长验证后才能继续';

  @override
  String get wakelockHint => '一般不用开，更费电';

  @override
  String get faultSection => '运行记录';

  @override
  String get faultNote => '上报出过什么问题、App 自己怎么处理的，只留最近一次';

  @override
  String get faultNone => '没有记录到异常';

  @override
  String get faultRescue => '上报卡住过，已自动重启定位或追踪';

  @override
  String get faultDegraded => '高德定位用不了，这段追踪改用系统定位';

  @override
  String get faultStorage => '手机存储写不进去（可能满了），位置先存在内存里接着发';

  @override
  String get faultDropped => '没发出去的记录太多，丢掉了最旧的一部分';

  @override
  String get faultCrash => '后台出过错，已自动恢复';
}
