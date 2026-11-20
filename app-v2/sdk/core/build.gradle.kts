import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidKotlinMultiplatformLibrary)
    alias(libs.plugins.sqldelight)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.vanniktechMavenPublish)
}

group = "org.traccar"
version = System.getenv("RELEASE_VERSION") ?: "0.0.1-SNAPSHOT"

sqldelight {
    databases {
        create("Database") {
            packageName.set("org.traccar.client.db")
        }
    }
}

kotlin {
    android {
        namespace = "org.traccar.client"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        androidResources.enable = true
        // 单元测试在 JVM 上跑（commonTest 和 androidHostTest），不要模拟器
        withHostTest {}
        // 高德定位 SDK 的混淆保留规则跟着库走，App 开混淆打正式包时自动带上
        optimization {
            consumerKeepRules.apply {
                publish = true
                file("src/androidMain/consumer-rules.pro")
            }
        }
    }

    val xcframework = XCFramework("TraccarClientSDK")

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "TraccarClientSDK"
            isStatic = true
            xcframework.add(this)
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
            implementation(libs.sqldelight.runtime)
            implementation(libs.koin.core)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.android)
            implementation(libs.androidx.activity)
            implementation(libs.sqldelight.android.driver)
            implementation(libs.amap.location)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        // 引擎测试要真的状态库：JVM 上的 SQLite
        getByName("androidHostTest").dependencies {
            implementation("app.cash.sqldelight:sqlite-driver:${libs.versions.sqldelight.get()}")
        }
    }
}

mavenPublishing {
    publishToMavenCentral(automaticRelease = true)
    signAllPublications()
    coordinates("org.traccar", "traccar-client-sdk", version.toString())
    pom {
        name.set("Traccar Client SDK")
        description.set("Kotlin Multiplatform location tracking SDK for Traccar.")
        url.set("https://github.com/traccar/traccar-client-sdk")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("tananaev")
                name.set("Anton Tananaev")
                email.set("anton@traccar.org")
            }
        }
        scm {
            url.set("https://github.com/traccar/traccar-client-sdk")
            connection.set("scm:git:https://github.com/traccar/traccar-client-sdk.git")
            developerConnection.set("scm:git:ssh://git@github.com/traccar/traccar-client-sdk.git")
        }
    }
}
