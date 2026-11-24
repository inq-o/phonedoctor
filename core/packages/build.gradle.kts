plugins {
    alias(libs.plugins.phonedoctor.android.library)
    alias(libs.plugins.phonedoctor.hilt)
}

android {
    namespace = "io.github.inq_o.phonedoctor.core.packages"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("boolean", "STRONG_MODE_ENABLED", "true")
    }
    buildTypes {
        release {
            // 출시 빌드는 끈다: 공장 초기화한 시연용 기기에서만 쓰는 기능이고(spec US8), 원스토어 심사에서 기기 관리자 용도를 따로 설명해야 한다.
            // 켜려면 app/src/release/AndroidManifest.xml의 관리자 수신기·프로비저닝 화면 제거도 뺀다
            buildConfigField("boolean", "STRONG_MODE_ENABLED", "false")
        }
    }
}

dependencies {
    api(projects.core.model)

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
