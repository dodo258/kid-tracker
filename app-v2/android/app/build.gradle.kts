import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// 正式签名放在仓库外：~/.config/gps-tracker/key.properties（storeFile 写 .jks 的绝对路径），和根目录旧 App 同一把。
// 文件不存在时 release 用 debug 签名，能装能测，但不能覆盖安装正式签名的包。
val keystorePropertiesFile = File(System.getProperty("user.home"), ".config/gps-tracker/key.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}
fun keystoreProperty(name: String): String =
    keystoreProperties.getProperty(name) ?: throw GradleException("$keystorePropertiesFile 缺少 $name")

// 高德 Key 不进 git：先读 app-v2/android/local.properties，没有再读根目录旧 App 的 android/local.properties（同一个 Key，
// 包名和签名一样），都没有就是空的，照样能编，运行时只用系统定位
fun amapKeyFrom(file: File): String? = file.takeIf { it.exists() }?.let { f ->
    Properties().apply { FileInputStream(f).use { load(it) } }.getProperty("AMAP_KEY")?.trim()?.takeIf { it.isNotEmpty() }
}
val amapKey = amapKeyFrom(rootProject.file("local.properties"))
    ?: amapKeyFrom(rootProject.file("../../android/local.properties"))
    ?: ""

android {
    // 和旧 App 同一个包名：覆盖安装，设备号、密码等本机数据接着用
    namespace = "com.dodo258.tracker"
    compileSdk = 37
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        applicationId = "com.dodo258.tracker"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        minSdk = 24
        targetSdk = flutter.targetSdkVersion
        versionCode = flutter.versionCode
        versionName = flutter.versionName
        manifestPlaceholders["AMAP_KEY"] = amapKey
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                keyAlias = keystoreProperty("keyAlias")
                keyPassword = keystoreProperty("keyPassword")
                storeFile = file(keystoreProperty("storeFile"))
                storePassword = keystoreProperty("storePassword")
            }
        }
    }
    buildTypes {
        release {
            signingConfig = if (keystorePropertiesFile.exists()) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    lint {
        disable.add("NullSafeMutableLiveData")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

// App 原生代码直接用 SDK：防卸载被停用时报告警（Tracker.reportTampering）、测试用录制（sense/SnapshotRecorder）；
// 版本号是占位，settings.gradle.kts 把它换成 app-v2/sdk/core 源码。原生单元测试（旧数据迁移 LegacyTrackerDb）要真的 org.json 和 SQLite
dependencies {
    implementation("org.traccar:traccar-client-sdk:1.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.core:core-ktx:1.13.1")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.xerial:sqlite-jdbc:3.53.4.0")
}

// 只出 arm64（P20、三星都是 64 位）：Flutter 插件会把 abiFilters 重置成三种，只能在打包时去掉别的 ABI
androidComponents {
    onVariants { variant ->
        variant.packaging.jniLibs.excludes.addAll("lib/armeabi-v7a/**", "lib/armeabi/**", "lib/x86/**", "lib/x86_64/**")
    }
}

if (!keystorePropertiesFile.exists()) {
    gradle.taskGraph.whenReady {
        if (allTasks.any { it.name.contains("Release") }) {
            logger.error("未找到 $keystorePropertiesFile，release 包改用 debug 签名（不能覆盖安装正式签名的包）")
        }
    }
}
