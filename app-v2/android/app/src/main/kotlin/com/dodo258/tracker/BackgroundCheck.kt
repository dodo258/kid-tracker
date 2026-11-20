package com.dodo258.tracker

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.IOException
import kotlin.concurrent.thread

/**
 * 自检清单的原生侧（从根目录旧 App 搬来）：读系统能检测的各项状态（含两个建议项）和本机信息、打开对应设置页、按品牌和项目打开厂商设置页。
 * 只读状态和打开设置页，不改任何系统设置。厂商项有没有生效不在这里读：SDK 按实际表现判（health/BackgroundHealth.kt）。
 */
class BackgroundCheck(private val activity: Activity) {

    fun register(messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "getStatus" -> result.success(status())
                "openSettings" -> result.success(openSettings(call.arguments as String))
                "openVendorSettings" -> result.success(openVendorSettings(call.arguments as String))
                else -> result.notImplemented()
            }
        }
    }

    private val packageName: String get() = activity.packageName

    private fun status(): Map<String, Any?> = mapOf(
        "items" to mapOf(
            "backgroundLocation" to backgroundLocation(),
            // Android 12 起定位可以只给「大概位置」，这时没有 ACCESS_FINE_LOCATION；
            // 更早的系统没有这个开关（给了定位就是精确的），没给定位由「始终允许」那项管
            "preciseLocation" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                state(granted(Manifest.permission.ACCESS_FINE_LOCATION))
            } else NOT_APPLICABLE,
            "batteryOptimization" to state(
                activity.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(packageName)
            ),
            "backgroundRestriction" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                state(!activity.getSystemService(ActivityManager::class.java).isBackgroundRestricted)
            } else NOT_APPLICABLE,
            "exactAlarm" to exactAlarm(),
            // 身体活动（计步器，判断层用）：Android 10 起是运行时权限，更早安装即有
            "motionPermission" to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                state(granted(Manifest.permission.ACTIVITY_RECOGNITION))
            } else NOT_APPLICABLE,
            "hibernation" to hibernation(),
            "notifications" to state(
                activity.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
            ),
            "locationService" to state(activity.isLocationEnabled()),
            "powerSave" to powerSave(),
            "wifiScan" to wifiScan(),
            // 省流量开着且本 App 不在不受限名单里
            "dataSaver" to state(
                activity.getSystemService(ConnectivityManager::class.java).restrictBackgroundStatus !=
                    ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED
            ),
        ),
        "brand" to brand(),
        "fingerprint" to Build.FINGERPRINT,
        "device" to device,
        "rom" to rom,
        "release" to Build.VERSION.RELEASE,
    )

    private fun state(ok: Boolean) = if (ok) OK else BAD

    private fun granted(permission: String) =
        activity.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    // 「始终允许」：Android 10 起是单独的后台定位权限，之前前台定位权限即包含后台
    private fun backgroundLocation() = state(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        else granted(Manifest.permission.ACCESS_FINE_LOCATION)
    )

    // 呼吸闹钟（SDK BreathAlarm）用精确闹钟；SDK 清单声明了 SCHEDULE_EXACT_ALARM，没声明时这项不适用
    private fun exactAlarm(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || !requestsPermission(Manifest.permission.SCHEDULE_EXACT_ALARM)) {
            return NOT_APPLICABLE
        }
        return state(activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms())
    }

    private fun requestsPermission(permission: String): Boolean {
        val info = activity.packageManager.getPackageInfo(packageName, PackageManager.GET_PERMISSIONS)
        return info.requestedPermissions?.contains(permission) == true
    }

    // 与 androidx PackageManagerCompat.getUnusedAppRestrictionsStatus 在 Android 11+ 的判断相同
    // （!isAutoRevokeWhitelisted 即「停用不常用应用」开着）；更早的系统只有 Play 商店的补丁版，无 GMS 手机没有。
    // 设备管理员已启用时系统豁免、开关灰着关不了，也算通过（见 HibernationRule）
    private fun hibernation() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        state(HibernationRule.ok(activity.packageManager.isAutoRevokeWhitelisted, Guard.adminActive(activity), Build.VERSION.SDK_INT))
    } else NOT_APPLICABLE

    private fun powerSave(): String {
        val power = activity.getSystemService(PowerManager::class.java)
        val locationMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) power?.locationPowerSaveMode else null
        return state(PowerSaveRule.ok(power?.isPowerSaveMode, locationMode))
    }

    // WLAN 开着，或关着但「WLAN 扫描」开着，定位才能用附近的 WLAN；都没有时室内只剩基站
    @Suppress("DEPRECATION") // isScanAlwaysAvailable 在 Android 10 标了弃用，仍返回系统开关的值
    private fun wifiScan(): String {
        val wifi = activity.applicationContext.getSystemService(WifiManager::class.java) ?: return NOT_APPLICABLE
        return state(wifi.isWifiEnabled || wifi.isScanAlwaysAvailable)
    }

    // 系统属性只在开机时定，进程内读一次就够；读 getprop 一次拿全部，免得每个键起一个进程
    private val properties: Map<String, String> by lazy {
        val process = try {
            ProcessBuilder("getprop").redirectErrorStream(true).start()
        } catch (e: IOException) {
            return@lazy emptyMap()
        }
        var result = emptyMap<String, String>()
        val reader = thread {
            result = process.inputStream.bufferedReader().useLines { lines ->
                lines.mapNotNull { PROPERTY.matchEntire(it) }.associate { it.groupValues[1] to it.groupValues[2] }
            }
        }
        // 读不完就不等了，只是少显示厂商系统版本
        reader.join(PROPERTY_TIMEOUT_MS)
        process.destroy()
        result
    }

    private fun property(vararg keys: String) = keys.firstNotNullOfOrNull { properties[it]?.takeIf(String::isNotBlank) }

    /** 商品名（如「Redmi K70」），读不到时用「品牌 型号」。 */
    private val device: String
        get() = property(
            "ro.product.marketname",
            "ro.vendor.oplus.market.name",
            "ro.oppo.market.name",
            "ro.vivo.market.name",
            "ro.config.marketing_name",
            "ro.product.vendor.marketname",
        ) ?: if (Build.MODEL.startsWith(Build.BRAND, ignoreCase = true)) Build.MODEL else "${Build.BRAND} ${Build.MODEL}"

    /** 厂商系统版本，如「HyperOS OS2.0」「HarmonyOS 4.2.0」；问 AI 时比安卓版本号有用。读不到为 null。 */
    private val rom: String?
        get() {
            property("ro.mi.os.version.name")?.let { return "HyperOS $it" }
            property("ro.miui.ui.version.name")?.let { return "MIUI $it" }
            property("hw_sc.build.platform.version")?.let { return "HarmonyOS $it" }
            property("ro.build.version.magic")?.let { return it.replace('_', ' ') }
            property("ro.build.version.emui")?.let { return it.replace('_', ' ') }
            property("ro.vivo.os.build.display.id")?.let { return it }
            property("ro.vivo.os.version")?.let { return "OriginOS $it" }
            property("ro.build.version.realmeui")?.let { return "realme UI $it" }
            property("ro.build.version.oplusrom", "ro.build.version.opporom")?.let { return "ColorOS $it" }
            property("ro.build.version.oneui")?.toIntOrNull()?.let { return "One UI ${it / 10000}.${it / 100 % 100}" }
            return null
        }

    private fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))

    /** 打开这项的设置页；没有专门页面或打不开时打开应用详情页并返回 false（界面再显示菜单路径）。 */
    private fun openSettings(item: String): Boolean {
        val intents = when (item) {
            "batteryOptimization" -> listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.fromParts("package", packageName, null)),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
            )
            "exactAlarm" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                listOf(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", packageName, null)))
            } else emptyList()
            "notifications" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                listOf(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else emptyList()
            "locationService" -> listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
            "powerSave" -> listOf(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS))
            "dataSaver" -> listOf(
                Intent(Settings.ACTION_IGNORE_BACKGROUND_DATA_RESTRICTIONS_SETTINGS, Uri.fromParts("package", packageName, null)),
            )
            "wifiScan" -> return openWifiScan()
            // 后台定位、精确位置、后台限制（Android 12 起在「电池」里）、停用不常用应用都在应用详情页
            else -> emptyList()
        }
        return startFirst(intents)
    }

    // 系统弹框「允许扫描 WLAN」：要用 startActivityForResult 打开，系统才知道是哪个 App 在请求（不加 NEW_TASK）。
    // 打不开时退到定位设置页（多数系统的「WLAN 扫描」在那里），界面再显示「设置里搜 WLAN 扫描」
    @Suppress("DEPRECATION") // ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE 在 Android 10 标了弃用，系统设置仍处理它
    private fun openWifiScan(): Boolean = try {
        activity.startActivityForResult(Intent(WifiManager.ACTION_REQUEST_SCAN_ALWAYS_AVAILABLE), REQUEST_WIFI_SCAN)
        true
    } catch (e: ActivityNotFoundException) {
        startFirst(emptyList(), listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
    } catch (e: SecurityException) {
        startFirst(emptyList(), listOf(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
    }

    /** 依次尝试这个品牌这一项的厂商设置页；都打不开时打开离它最近的上级页（没有就应用详情页）并返回 false。 */
    private fun openVendorSettings(item: String): Boolean =
        startFirst(vendorIntents(brand(), item), vendorFallbacks(brand(), item))

    private fun startFirst(intents: List<Intent>, fallbacks: List<Intent> = emptyList()): Boolean {
        for (intent in intents) {
            if (start(intent)) return true
        }
        (fallbacks + appDetails()).firstOrNull(::start)
        return false
    }

    // 厂商页面多半没导出或换了类名：没找到抛 ActivityNotFoundException，没导出抛 SecurityException。
    // 设置页开在自己的任务里：不压在本 App 任务栈顶，点桌面图标回来就是 App 自己
    private fun start(intent: Intent): Boolean = try {
        activity.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    } catch (e: SecurityException) {
        false
    }

    private fun brand(): String = Brand.of(Build.MANUFACTURER)

    // 厂商页面没有官方文档，类名取自社区整理（见 DECISIONS.md「保活自检页」「稳定运行设置清单」），按新到旧排。
    // item 是清单里的厂商项：autostart、associatedLaunch、backgroundActivity、powerSaving
    // （允许联网、锁屏后不断网、多任务加锁没有可靠的页面，界面只显示路径，不会调到这里）
    private fun vendorIntents(brand: String, item: String): List<Intent> {
        fun component(pkg: String, cls: String) = Intent().setComponent(ComponentName(pkg, cls))
        // 华为/荣耀的自启动、关联启动、后台活动都在「应用启动管理」同一页
        val huawei = listOf(
            component("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
            component("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
        )
        val oppoStartup = listOf(
            component("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
            component("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
            component("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
        )
        return when (brand to item) {
            "huawei" to "autostart", "huawei" to "associatedLaunch", "huawei" to "backgroundActivity" -> huawei
            "honor" to "autostart", "honor" to "associatedLaunch", "honor" to "backgroundActivity" -> listOf(
                component("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
            ) + huawei
            "xiaomi" to "autostart" -> listOf(
                component("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
            )
            // 本 App 的「省电策略」页，要带包名和应用名
            "xiaomi" to "powerSaving" -> listOf(
                component("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity")
                    .putExtra("package_name", packageName)
                    .putExtra("package_label", activity.applicationInfo.loadLabel(activity.packageManager).toString()),
            )
            "oppo" to "autostart" -> oppoStartup
            "oppo" to "associatedLaunch" -> listOf(
                component("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
            ) + oppoStartup
            "vivo" to "autostart" -> listOf(
                component("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                component("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
            )
            "vivo" to "powerSaving" -> listOf(
                component("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity"),
                component("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity"),
            )
            "samsung" to "powerSaving" -> listOf(
                component("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
                component("com.samsung.android.sm_cn", "com.samsung.android.sm.battery.ui.BatteryActivity"),
            )
            // OPPO「耗电管理」在应用详情页里；其他组合没有专门页面
            else -> emptyList()
        }
    }

    // 鸿蒙 3（华为 P20 实测）的「应用启动管理」页要系统签名权限
    // com.huawei.permission.external_app_settings.USE_COMPONENT，第三方打开被拒；
    // 它的上级「设置 → 应用和服务」能打开，进去点一下「应用启动管理」就到
    private fun vendorFallbacks(brand: String, item: String): List<Intent> =
        if ((brand == "huawei" || brand == "honor") && item in HUAWEI_STARTUP_ITEMS) listOf(
            Intent().setComponent(
                ComponentName("com.android.settings", "com.android.settings.Settings\$AppAndNotificationDashboardActivity")
            ),
        ) else emptyList()

    private companion object {
        val HUAWEI_STARTUP_ITEMS = setOf("autostart", "associatedLaunch", "backgroundActivity")
        const val CHANNEL = "com.dodo258.tracker/background_check"
        const val OK = "ok"
        const val BAD = "bad"
        const val NOT_APPLICABLE = "na"
        const val PROPERTY_TIMEOUT_MS = 2000L
        const val REQUEST_WIFI_SCAN = 7303
        val PROPERTY = Regex("""^\[([^\]]+)]: \[(.*)]$""")
    }
}

/** 系统定位总开关开着没有。 */
private fun Context.isLocationEnabled(): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        return getSystemService(android.location.LocationManager::class.java).isLocationEnabled
    }
    @Suppress("DEPRECATION")
    return Settings.Secure.getInt(contentResolver, Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
        Settings.Secure.LOCATION_MODE_OFF
}
