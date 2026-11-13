import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.meetroute.app"
    compileSdk = 34

    // AAOS의 주행 제한 상태를 읽는다. 런타임에는 차량 이미지가 제공한다.
    useLibrary("android.car")

    defaultConfig {
        applicationId = "com.meetroute.app"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // 서버 주소는 비밀값이 아니다. 앱 설정 화면에서도 바꿀 수 있다.
        val serverUrl = providers.gradleProperty("meetroute.serverUrl")
            .getOrElse("http://10.0.2.2:8000")
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"$serverUrl\"")
    }

    // 서명 키는 저장소 밖에 둔다. -Pflock.signing=<keystore.properties 경로>로 넘기면 release를 서명한다.
    // 파일 내용: storeFile, storePassword, keyAlias, keyPassword
    val signingFile = providers.gradleProperty("flock.signing").orNull?.let(::file)
    if (signingFile != null && signingFile.isFile) {
        val keyProps = Properties()
        signingFile.inputStream().use { keyProps.load(it) }
        signingConfigs {
            create("release") {
                storeFile = file(keyProps.getProperty("storeFile"))
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Pleos SDK가 kotlinx.serialization/AIDL을 쓰므로 축소 규칙을 검증하기 전까지 끈다.
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
    // 전기차 배터리 잔량(EvBattery). 대기 시간을 충전 시간으로 쓰는 추천에 쓴다.
    implementation(libs.pleos.vehicle)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
