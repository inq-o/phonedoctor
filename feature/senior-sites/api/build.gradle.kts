plugins {
    alias(libs.plugins.phonedoctor.android.feature.api)
}

android {
    namespace = "io.github.inq_o.phonedoctor.feature.seniorsites.api"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField("boolean", "SITE_ALERTS_ENABLED", "true")
    }
    buildTypes {
        release {
            // 출시 빌드는 끈다: 지금 시행 중인 개인정보처리방침(docs/privacy-policy.md)에 알림 접근이 없다.
            // 켜려면 방침에 알림 접근(Chrome 사이트 알림만 살펴보고 다른 앱 알림은 바로 버림)을 적어 시행하고,
            // app/src/release/AndroidManifest.xml의 알림 리스너 제거를 뺀다
            buildConfigField("boolean", "SITE_ALERTS_ENABLED", "false")
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
