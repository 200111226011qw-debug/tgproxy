import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 正式签名：优先读环境变量，其次读项目根的 keystore.properties（已被 .gitignore 忽略）。
// 两者都没有时 release 保持未签名，保证没有密钥材料也能构建（只产出 unsigned 包）。
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.reader(Charsets.UTF_8).use { load(it) }
    }
}
fun signingProp(env: String, key: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(key)?.takeIf { it.isNotBlank() }

android {
    namespace = "com.tgproxy"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tgproxy"
        minSdk = 24
        targetSdk = 35
        versionCode = 12
        versionName = "2.2.1"
    }

    signingConfigs {
        // 仅在确实拿到密钥材料时才创建 release 签名配置
        val storePath = signingProp("TGPROXY_KEYSTORE", "storeFile")
        if (storePath != null) {
            create("release") {
                storeFile = file(storePath)
                storePassword = signingProp("TGPROXY_STORE_PASSWORD", "storePassword")
                keyAlias = signingProp("TGPROXY_KEY_ALIAS", "keyAlias")
                keyPassword = signingProp("TGPROXY_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 没有密钥材料时保持未签名，不让构建失败
            signingConfig = signingConfigs.findByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.okhttp)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.ui.test.junit4)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
