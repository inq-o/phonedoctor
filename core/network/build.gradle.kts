plugins {
    alias(libs.plugins.phonedoctor.android.library)
    alias(libs.plugins.phonedoctor.hilt)
    alias(libs.plugins.kotlin.serialization)
}

// 끝의 /는 Retrofit 기준 주소에 필요
val RELEASE_API_BASE_URL = "https://phonedoctor-api-236445024434.asia-northeast3.run.app/v1/"

// 개발 빌드 서버 주소. 평문은 디버그 network_security_config가 허용한 10.0.2.2만 쓸 수 있다
fun debugApiBaseUrl(): String {
    val url = providers.gradleProperty("phonedoctor.apiBaseUrl").orNull?.takeIf { it.isNotBlank() }
        ?: return "http://10.0.2.2:8080/v1/"
    require(url.endsWith("/v1/")) { "phonedoctor.apiBaseUrl은 /v1/로 끝나야 함: $url" }
    require(url.startsWith("https://") || url.startsWith("http://10.0.2.2:")) {
        "phonedoctor.apiBaseUrl은 https:// 또는 http://10.0.2.2: 만: $url"
    }
    return url
}

android {
    namespace = "io.github.inq_o.phonedoctor.core.network"
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        // 개발 빌드: 기본은 에뮬레이터 → 맥에서 띄운 로컬 서버 (server/README.md).
        // 배포한 서버로 붙이려면 -Pphonedoctor.apiBaseUrl=$RELEASE_API_BASE_URL (App Check 디버그 토큰 등록 필요)
        buildConfigField("String", "API_BASE_URL", "\"${debugApiBaseUrl()}\"")
        buildConfigField("boolean", "FAMILY_ENABLED", "true")
    }
    buildTypes {
        release {
            // 배포한 서버 (Cloud Run 서울, server/README.md 배포)
            buildConfigField("String", "API_BASE_URL", "\"$RELEASE_API_BASE_URL\"")
            // 출시 빌드는 가족 연결을 끈다: 지금 시행 중인 개인정보처리방침(docs/privacy-policy.md)은 기기 밖 전송이 없다고 적었다.
            // 켜려면 가족 연결 판 방침을 시행하고, 위 주소를 실제 주소로 바꾸고, app/src/release/AndroidManifest.xml의 권한 제거를 뺀다 (T057)
            buildConfigField("boolean", "FAMILY_ENABLED", "false")
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
    implementation(libs.firebase.appcheck.playintegrity)
    debugImplementation(libs.firebase.appcheck.debug)

    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.json.schema.validator)
}
