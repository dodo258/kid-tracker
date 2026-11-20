package com.dodo258.tracker

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.security.MessageDigest

/**
 * App 内更新的原生部分（Dart 侧 lib/update/apk_updater.dart 负责下载和核对 SHA-256）：
 * 缓存目录、「安装未知应用」权限、核对安装包身份、用 FileProvider + ACTION_VIEW 弹系统安装确认。
 * 同签名覆盖安装，数据都保留；装完系统发 MY_PACKAGE_REPLACED，SDK 的 BootReceiver 恢复追踪。
 *
 * 核对身份（审计 M1）：交给系统前确认是本 App 的包名、和装着的同一张签名证书、版本号比装着的大。
 * latest.json 写错地址、服务器被换了包都在这里拦下，不弹别的 App 的安装框，也不让系统去报「签名冲突」。
 */
class UpdateChannel(private val activity: Activity) {
    fun register(messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL).setMethodCallHandler { call, result ->
            when (call.method) {
                "cacheDir" -> result.success(activity.cacheDir.absolutePath)
                "canInstall" -> result.success(canInstall())
                "openInstallSettings" -> result.success(openInstallSettings())
                "verify" -> {
                    val problem = UpdateIdentity.problem(updateFile(call.argument<String>("path")), ::verify)
                    if (problem != null) Log.w(TAG, "Update apk rejected: $problem")
                    result.success(problem)
                }
                "install" -> result.success(install(call.argument<String>("path")))
                else -> result.notImplemented()
            }
        }
    }

    private fun canInstall() =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || activity.packageManager.canRequestPackageInstalls()

    private fun openInstallSettings(): Boolean {
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", activity.packageName, null))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }
        return start(intent)
    }

    /** 只认 update/ 目录里的文件：FileProvider 也只开放这个目录（res/xml/update_paths.xml）。 */
    private fun updateFile(path: String?): File? {
        val dir = File(activity.cacheDir, DIR).canonicalFile
        val apk = path?.let { File(it).canonicalFile } ?: return null
        return apk.takeIf { it.parentFile == dir && it.isFile }.also {
            if (it == null) Log.w(TAG, "Update apk not in $dir or missing: $apk")
        }
    }

    /** 安装包对不对：对返回 null，不对返回原因 package / signature / version / unreadable。 */
    private fun verify(apk: File): String? {
        val pm = activity.packageManager
        val archive = archiveInfo(pm, apk.path) ?: run {
            Log.w(TAG, "Update apk unreadable: ${apk.path} ${apk.length()} bytes")
            return "unreadable"
        }
        if (archive.packageName != activity.packageName) return "package"
        val installed = installedInfo(pm)
        val ours = UpdateIdentity.signers(installed)
        val theirs = UpdateIdentity.signers(archive)
        if (theirs.isEmpty() || theirs != ours) {
            Log.w(TAG, "Update apk signers $theirs, installed $ours")
            return "signature"
        }
        if (PackageInfoCompat.getLongVersionCode(archive) <= PackageInfoCompat.getLongVersionCode(installed)) return "version"
        return null
    }

    @Suppress("DEPRECATION")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? {
        // 安卓 9 起读签名证书；个别系统读安装包时不给，退回旧的 GET_SIGNATURES 再读一次
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
                ?.takeIf { UpdateIdentity.signers(it).isNotEmpty() }?.let { return it }
        }
        return pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
    }

    @Suppress("DEPRECATION")
    private fun installedInfo(pm: PackageManager): PackageInfo =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(activity.packageName, PackageManager.GET_SIGNATURES)
        }

    private fun install(path: String?): Boolean {
        val apk = updateFile(path) ?: return false
        if (verify(apk) != null) return false
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.update_provider", apk)
        return start(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        )
    }

    private fun start(intent: Intent) = try {
        activity.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    companion object {
        const val CHANNEL = "com.dodo258.tracker/update"
        private const val TAG = "Update"
        const val DIR = "update"
    }
}

object UpdateIdentity {
    /** 找不到文件是 unreadable；找到了交给 [check]，它返回 null 就是对的（别写成 `?.let(check) ?: "unreadable"`，对的包也会被拦） */
    fun problem(apk: File?, check: (File) -> String?): String? = if (apk == null) "unreadable" else check(apk)

    /** 包的签名证书（SHA-256 摘要，十六进制）：多签名者取全部，单签名者取当前那张（证书轮换过时是历史里最后一张）。 */
    @Suppress("DEPRECATION")
    fun signers(info: PackageInfo): Set<String> {
        val certs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && info.signingInfo != null) {
            val signing = info.signingInfo!!
            if (signing.hasMultipleSigners()) signing.apkContentsSigners.toList()
            else listOfNotNull(signing.signingCertificateHistory?.lastOrNull())
        } else {
            info.signatures?.toList().orEmpty()
        }
        return certs.map { digest(it.toByteArray()) }.toSet()
    }

    fun digest(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** 自己的 FileProvider 子类：清单合并按类名合并 provider，直接用 androidx 的会和插件（如分享）声明的撞在一起。 */
class UpdateFileProvider : FileProvider()
