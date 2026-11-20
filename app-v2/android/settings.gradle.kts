pluginManagement {
    val flutterSdkPath = run {
        val properties = java.util.Properties()
        file("local.properties").inputStream().use { properties.load(it) }
        val flutterSdkPath = properties.getProperty("flutter.sdk")
        require(flutterSdkPath != null) { "flutter.sdk not set in local.properties" }
        flutterSdkPath
    }

    includeBuild("$flutterSdkPath/packages/flutter_tools/gradle")

    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.flutter.flutter-plugin-loader") version "1.0.0"
    // 和原版 SDK v1.1.4（sdk/gradle/libs.versions.toml）同一个 AGP、Kotlin：composite build 里只能有一个版本，SDK 不用改
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
}

include(":app")

// SDK 用 app-v2/sdk/ 源码构建：Flutter 插件依赖的 Maven 坐标替换成 sdk/core 工程
includeBuild("../sdk") {
    dependencySubstitution {
        substitute(module("org.traccar:traccar-client-sdk")).using(project(":core"))
    }
}
