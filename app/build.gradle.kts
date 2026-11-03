plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "de.bewegungswelt.radar"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "de.bewegungswelt.radar"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "0.2.1"
    }

    signingConfigs {
        create("release") {
            System.getenv("RADAR_KEYSTORE_PATH")?.let { storeFile = file(it) }
            storePassword = System.getenv("RADAR_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RADAR_KEY_ALIAS")
            keyPassword = System.getenv("RADAR_KEY_PASSWORD")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<Test>().configureEach {
    // Robolectric's file-descriptor bridge needs access to this JDK internal API.
    jvmArgs("--add-exports=java.base/jdk.internal.access=ALL-UNNAMED")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Never produce an unsigned APK that could accidentally be published as an update.
tasks.matching { it.name == "validateSigningRelease" }.configureEach {
    doFirst {
        check(listOf("RADAR_KEYSTORE_PATH", "RADAR_KEYSTORE_PASSWORD", "RADAR_KEY_ALIAS", "RADAR_KEY_PASSWORD")
            .all { !System.getenv(it).isNullOrBlank() }) {
            "Release signing is not configured. See RELEASE.md."
        }
    }
}

dependencies {
    implementation(project(":core:availability"))
    implementation(project(":provider-locaboo"))
    implementation(libs.work.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.okhttp)
    implementation(libs.gson)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.compose.ui.test.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.work.testing)
}
