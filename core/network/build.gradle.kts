plugins {
    alias(libs.plugins.phonedoctor.android.library)
    alias(libs.plugins.phonedoctor.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.inq_o.phonedoctor.core.network"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        // 에뮬레이터 → 맥에서 띄운 로컬 서버 (server/README.md). 끝의 /는 Retrofit 기준 주소에 필요
        buildConfigField("String", "API_BASE_URL", "\"http://10.0.2.2:8080/v1/\"")
    }
    buildTypes {
        release {
            // TODO(배포): Cloud Run 주소가 나오면 바꾼다 (T053 배포)
            buildConfigField("String", "API_BASE_URL", "\"https://api.phonedoctor.invalid/v1/\"")
        }
    }
    testOptions {
        unitTests.all {
            // 요청·응답 본문을 API 계약과 대조한다 (서버 시험과 같은 파일)
            val openapi = rootDir.resolve("specs/001-mvp/contracts/openapi.yaml")
            it.inputs.file(openapi).withPathSensitivity(PathSensitivity.NONE)
            it.systemProperty("openapi.path", openapi.absolutePath)
        }
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    implementation(libs.firebase.appcheck)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json.schema.validator)
}
