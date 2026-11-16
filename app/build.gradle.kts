import com.android.build.gradle.internal.cxx.configure.gradleLocalProperties
import java.util.Properties

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.gradleVersionsPlugin)
    alias(libs.plugins.androidKsp)
    kotlin("kapt")
    id("kotlin-parcelize")
}

val properties = gradleLocalProperties(rootDir, providers)
val mStoreFile: String? = properties.getProperty("storeFile")
val mStorePassword: String? = properties.getProperty("storePassword")
val mKeyAlias: String? = properties.getProperty("keyAlias")
val mKeyPassword: String? = properties.getProperty("keyPassword")
val httpDnsId = properties.getProperty("httpDnsId") ?: "\"\""
val httpDnsSecret = properties.getProperty("httpDnsSecret") ?: "\"\""

val appVersionCode = 95
val appVersionName = "3.3"
val appVersionSuffix = ""

// Fork（tiger-git2022）：自用版 custom 构建类型
// -Ps1SigningFile=xxx.properties 指定签名（storeFile/storePassword/keyAlias/keyPassword），不传时用 debug 签名（CI 检查用）
// -Ptiger.version=X.Y.Z 由发布工作流传入（自己的版本号，X.Y 在 .github/fork-version），
// versionCode = X * 10000000 + Y * 10000 + Z；本地不传时用 X.Y.0
val customSigning = Properties().apply {
    val path = findProperty("s1SigningFile")?.toString()
    if (path != null && file(path).exists()) {
        file(path).inputStream().use { load(it) }
    }
}
val forkSeries = rootProject.file(".github/fork-version").readText().trim()
val tigerVersion = (findProperty("tiger.version") ?: "$forkSeries.0").toString()
val tigerParts = tigerVersion.split(".").map { it.toInt() }
require(tigerParts.size == 3) { "tiger.version must be X.Y.Z, got $tigerVersion" }
val tigerVersionCode = tigerParts[0] * 10000000 + tigerParts[1] * 10000 + tigerParts[2]

android {
    namespace = "me.ykrank.s1next"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "me.ykrank.s1next"
        minSdk = 26
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = appVersionCode
        versionName = "${appVersionName}.${appVersionCode}${appVersionSuffix}"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }
    signingConfigs {
        if (!mStoreFile.isNullOrEmpty()) {
            create("release") {
                keyAlias = mKeyAlias
                keyPassword = mKeyPassword
                storeFile = file(mStoreFile)
                storePassword = mStorePassword
            }
        }
        // Fork
        if (!customSigning.isEmpty) {
            create("custom") {
                storeFile = file(customSigning.getProperty("storeFile").replaceFirst(Regex("^/([a-zA-Z])/"), "$1:/"))
                storePassword = customSigning.getProperty("storePassword")
                keyAlias = customSigning.getProperty("keyAlias")
                keyPassword = customSigning.getProperty("keyPassword")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        dataBinding = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/LICENSE"
            excludes += "META-INF/LICENSE.txt"
            excludes += "META-INF/NOTICE"
            excludes += "META-INF/NOTICE.txt"
        }
    }
    buildTypes {
        debug {
            multiDexEnabled = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }

        create("alpha") {
            multiDexEnabled = true
            applicationIdSuffix = ".alpha"
            versionNameSuffix = "-alpha"
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")

            matchingFallbacks += listOf("release", "debug")
        }

        release {
            multiDexEnabled = true
            if (signingConfigs.findByName("release") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android.txt"), "proguard-rules.pro")
        }

        // Fork：自用版，和官方版并存安装，带域名迁移、自动黑魔法等改动
        create("custom") {
            initWith(getByName("release"))
            applicationIdSuffix = ".custom"
            signingConfig = signingConfigs.findByName("custom") ?: signingConfigs.getByName("debug")
            matchingFallbacks += listOf("release")
        }
    }

    // Fork：custom 复用 release 的构建类型代码（BuildTypeModule、PreApp）
    sourceSets.getByName("custom").java.srcDir("src/release/java")

    buildTypes.forEach {
        it.buildConfigField("String", "DB_NAME", "\"s1.db\"")
        it.buildConfigField("String", "HTTP_DNS_ID", httpDnsId)
        it.buildConfigField("String", "HTTP_DNS_SECRET", httpDnsSecret)
    }

    flavorDimensions += "market"
    productFlavors {
        create("play") {
            dimension = "market"
            manifestPlaceholders["APP_CHANNEL"] = "play.google.com"
            versionNameSuffix = "-play"
        }
        create("normal") {
            dimension = "market"
            manifestPlaceholders["APP_CHANNEL"] = "normal"
        }
    }
    androidResources {
        generateLocaleConfig = true
    }
}

// Fork：custom 使用自己的版本号和 APK 文件名
androidComponents {
    onVariants(selector().withBuildType("custom")) { variant ->
        variant.outputs.forEach { output ->
            output.versionCode.set(tigerVersionCode)
            output.versionName.set(tigerVersion)
        }
    }
}
android.applicationVariants.all {
    if (buildType.name == "custom") {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "s1next-custom-v$tigerVersion.apk"
        }
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val alphaImplementation by configurations
val customImplementation by configurations // Fork
dependencies {
    implementation(fileTree("libs") { include("*.jar", "*.aar") })

    implementation(project(":library"))
    implementation(project(":JKeyboardPanelSwitch"))

    kapt(libs.databinding.compiler)
    implementation(libs.paging)

    implementation(libs.bugly.nativecrashreport)

    implementation(libs.dagger)
    kapt(libs.dagger.compiler)

    implementation(libs.androidx.transition)

    implementation(libs.okhttp.urlconnection)
    implementation(libs.okhttp.coroutines)
    implementation(libs.retrofit2)
    implementation(libs.retrofit2.adapter.rxjava2)
    implementation(libs.retrofit2.converter.jackson)
    implementation(libs.retrofit2.converter.scalars)

    implementation(libs.jackson.kotlin)
    implementation(libs.jackson.databind)

    implementation(libs.paperparcel)
    implementation(libs.paperparcel.kotlin) // Optional
    implementation(libs.paperparcel.api)
    kapt(libs.paperparcel.compiler)

    ksp(libs.glide.ksp)

    implementation(libs.photoview)
    implementation(libs.quicksidebar)

//  flipper
    releaseImplementation(libs.flipper.noop)
    alphaImplementation(libs.flipper.noop)
    customImplementation(libs.flipper.noop) // Fork
    debugImplementation(libs.flipper)
    debugImplementation(libs.soloader)
    debugImplementation(libs.flipper.network.plugin)
    debugImplementation(libs.flipper.leakcanary.plugin)

    implementation(libs.alicloud.android.httpdns)

    //room
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
}
