// Pleos NaviHelper·FusedLocation 동작을 버튼별로 확인하는 최소 시험 APK. 결과는 docs/sdk-integration.md §5.2, §6에 있다.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.meetroute.smoke"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.meetroute.smoke"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
}
