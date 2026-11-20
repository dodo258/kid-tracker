import 'package:traccar_client_sdk/traccar_client_sdk.dart';

import '../preferences.dart';
import 'background_check.dart';
import 'guard.dart';

/// done：✓；todo：✗，算进「还差 N 项」；pending：厂商项还在等实际表现（待验证），不算；notApplicable：这台手机不需要。
enum EntryState { done, todo, pending, notApplicable }

/// 系统能读到状态的项：每次回到 App 现读，开了就打勾。
class AutoEntry {
  const AutoEntry(this.item, this.state);

  final CheckItem item;
  final EntryState state;
}

/// 厂商设置：按实际表现判（待验证 → 已生效 / 没生效），家长不用手动打勾。
class VendorEntry {
  const VendorEntry(this.item, this.judgement);

  final VendorItem item;
  final HealthJudgement judgement;

  EntryState get state => switch (judgement.verdict) {
        HealthVerdict.ok => EntryState.done,
        HealthVerdict.bad => EntryState.todo,
        HealthVerdict.pending => EntryState.pending,
      };
}

/// 各品牌要设的厂商项，顺序即显示顺序（从根目录旧 App 搬来，品牌和原话见 DECISIONS「稳定运行设置清单」）。
List<VendorItem> vendorItemsFor(String brand) => switch (brand) {
      // 鸿蒙 3（P20 实测）：本 App 在多任务里一直看不到、没法加锁，一键清理也清不掉它，不用列
      'huawei' => const [
          VendorItem.autostart,
          VendorItem.associatedLaunch,
          VendorItem.backgroundActivity,
          VendorItem.networkAccess,
          VendorItem.screenOffNetwork,
        ],
      'honor' => const [
          VendorItem.autostart,
          VendorItem.associatedLaunch,
          VendorItem.backgroundActivity,
          VendorItem.networkAccess,
          VendorItem.screenOffNetwork,
          VendorItem.recentsLock,
        ],
      'xiaomi' => const [
          VendorItem.autostart,
          VendorItem.powerSaving,
          VendorItem.networkAccess,
          VendorItem.screenOffNetwork,
          VendorItem.recentsLock,
        ],
      'oppo' => const [
          VendorItem.autostart,
          VendorItem.associatedLaunch,
          VendorItem.powerSaving,
          VendorItem.networkAccess,
          VendorItem.screenOffNetwork,
          VendorItem.recentsLock,
        ],
      'vivo' => const [
          VendorItem.autostart,
          VendorItem.powerSaving,
          VendorItem.networkAccess,
          VendorItem.screenOffNetwork,
          VendorItem.recentsLock,
        ],
      // 三星：界面启动时把本 App 从多任务里隐藏（MainActivity.onResume），没卡片可锁；划掉卡片也不影响前台服务（11-19 实测）
      'samsung' => const [VendorItem.powerSaving],
      _ => const [VendorItem.autostart, VendorItem.recentsLock],
    };

class Checklist {
  const Checklist({
    required this.status,
    required this.auto,
    required this.suggestions,
    required this.vendor,
    required this.admin,
    required this.password,
    this.adminLost = false,
    this.lastFault,
  });

  final BackgroundStatus status;

  /// 系统能读的必需项。
  final List<AutoEntry> auto;

  /// 「建议」一组（WLAN 扫描、省流量）：✗ 也不算进还差 N 项。
  final List<AutoEntry> suggestions;
  final List<VendorEntry> vendor;

  /// 防卸载（设备管理员）开没开。
  final EntryState admin;

  /// 家长开过的防卸载被停用了（已报给家长）。
  final bool adminLost;

  /// App 密码设没设。
  final EntryState password;

  /// 最近一次异常（降级、重建、丢弃、写不进存储），没有是 null。只给家长看，不算进还差几项。
  final LastFault? lastFault;

  /// 「还差 N 项」：只数 ✗（系统项没开、厂商项没生效、防卸载没开、没设密码），不数待验证、不适用和建议项。
  int get remaining => blocking + vendor.where((e) => e.state == EntryState.todo).length;

  /// 打开追踪前要先弄好的：系统项、防卸载、App 密码。厂商项不卡——它们要开着追踪才验证得了。
  int get blocking => [...auto.map((e) => e.state), admin, password].where((s) => s == EntryState.todo).length;

  List<AutoEntry> get applicableAuto => [for (final e in auto) if (e.state != EntryState.notApplicable) e];

  List<AutoEntry> get applicableSuggestions => [for (final e in suggestions) if (e.state != EntryState.notApplicable) e];

  List<AutoEntry> get notApplicable =>
      [for (final e in [...auto, ...suggestions]) if (e.state == EntryState.notApplicable) e];
}

Checklist buildChecklist(
  BackgroundStatus status, {
  required Map<HealthEvidence, HealthJudgement> health,
  required GuardState guard,
  required bool passwordSet,
  LastFault? lastFault,
}) {
  AutoEntry autoEntry(CheckItem item) => AutoEntry(item, switch (status.items[item]!) {
        CheckState.ok => EntryState.done,
        CheckState.bad => EntryState.todo,
        CheckState.na => EntryState.notApplicable,
      });
  return Checklist(
    status: status,
    auto: [for (final item in CheckItem.values) if (!item.suggestion) autoEntry(item)],
    suggestions: [for (final item in CheckItem.values) if (item.suggestion) autoEntry(item)],
    vendor: [
      for (final item in vendorItemsFor(status.brand))
        VendorEntry(item, health[item.evidence] ?? const HealthJudgement(HealthVerdict.pending)),
    ],
    admin: guard.admin ? EntryState.done : EntryState.todo,
    adminLost: guard.lost,
    password: passwordSet ? EntryState.done : EntryState.todo,
    lastFault: lastFault,
  );
}

/// 首页提示：首次打开、系统升级后（打开过清单才消失）、没设 App 密码，或清单里还有 ✗。
enum HomePrompt { none, firstRun, systemUpdated, noPassword, incomplete }

HomePrompt homePrompt(Checklist checklist, {required String? savedFingerprint}) {
  if (savedFingerprint == null) return HomePrompt.firstRun;
  if (savedFingerprint != checklist.status.fingerprint) return HomePrompt.systemUpdated;
  if (checklist.password == EntryState.todo) return HomePrompt.noPassword;
  return checklist.remaining > 0 ? HomePrompt.incomplete : HomePrompt.none;
}

/// 现读一遍：系统项、厂商项的实际表现、防卸载、App 密码。
Future<Checklist> loadChecklist() async => buildChecklist(
      await BackgroundCheck.status(),
      health: await BackgroundCheck.health(),
      guard: await Guard.state(),
      passwordSet: (Preferences.instance.getString(Preferences.password) ?? '').isNotEmpty,
      lastFault: await BackgroundCheck.lastFault(),
    );
