/*
 * Copyright 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File
import java.util.Properties

plugins {
    alias(libs.plugins.phonedoctor.android.application)
    alias(libs.plugins.phonedoctor.android.application.compose)
    alias(libs.plugins.phonedoctor.hilt)
}

// Firebase 설정(google-services.json)은 커밋하지 않는다. 파일이 있는 로컬 빌드에서만 붙이고, CI는 Firebase 없이 빌드한다
if (file("google-services.json").exists()) {
    apply(plugin = libs.plugins.gms.get().pluginId)
}

android {
    defaultConfig {
        applicationId = "io.github.inq_o.phonedoctor"
        versionCode = 1
        versionName = "0.1.0" // X.Y.Z; X = Major, Y = minor, Z = Patch level

        // Custom test runner to set up Hilt dependency graph
        testInstrumentationRunner = "io.github.inq_o.phonedoctor.core.testing.HiltTestRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    // 출시 서명: 레포 밖 ~/.phonedoctor/release.properties(또는 -Pphonedoctor.releaseProps=경로)가 있을 때만 쓴다 (키와 비밀번호는 레포·CI에 두지 않음).
    // 원스토어에는 "ONE store 앱 서명 선택해제(서명한 APK 등록)"로 올려 이 키를 유지한다 (docs/onestore-listing.md 2절)
    val releaseProps = (providers.gradleProperty("phonedoctor.releaseProps").orNull?.let(::File)
        ?: File(System.getProperty("user.home"), ".phonedoctor/release.properties"))
        .takeIf { it.isFile }
        ?.let { file -> Properties().apply { file.inputStream().use { load(it) } } }
    if (releaseProps != null) {
        signingConfigs {
            create("release") {
                storeFile = File(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseProps != null) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"),
                          "proguard-rules.pro")
        }
    }
    packaging {
        resources {
            excludes.add("/META-INF/{AL2.0,LGPL2.1}")
        }
    }
    namespace = "io.github.inq_o.phonedoctor"
}

// 출시 빌드가 지금 시행 중인 개인정보처리방침(인터넷 권한 없음)을 지키는지 확인한다. CI가 돌리는 ./gradlew test에 건다
androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        val manifest = variant.artifacts.get(com.android.build.api.artifact.SingleArtifact.MERGED_MANIFEST)
        val verify = tasks.register("verify${variant.name.replaceFirstChar { it.uppercase() }}Permissions") {
            inputs.file(manifest)
            doLast {
                val text = manifest.get().asFile.readText()
                val banned = listOf(
                    "android.permission.INTERNET",
                    "com.google.android.c2dm.permission.RECEIVE",
                    "com.google.android.providers.gsf.permission.READ_GSERVICES",
                )
                val found = banned.filter { "android:name=\"$it\"" in text }
                check(found.isEmpty()) { "출시 빌드에 통신 권한이 있음: $found (app/src/release/AndroidManifest.xml 참고)" }
                // 사이트 광고 알림(US10)은 방침에 알림 접근을 적기 전까지 출시 빌드에서 뺀다 (feature/senior-sites/api)
                check("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE" !in text) { "출시 빌드에 알림 리스너가 있음 (app/src/release/AndroidManifest.xml 참고)" }
                // 강력 모드(US8)의 기기 관리자도 출시 빌드에서 뺀다 (core/packages STRONG_MODE_ENABLED)
                check("android.permission.BIND_DEVICE_ADMIN" !in text) { "출시 빌드에 기기 관리자가 있음 (app/src/release/AndroidManifest.xml 참고)" }
                listOf("StrongModeAdminReceiver", "StrongModeProvisioningActivity").forEach { component ->
                    check(component !in text) { "출시 빌드에 강력 모드 컴포넌트가 있음: $component (app/src/release/AndroidManifest.xml 참고)" }
                }
                // 휴대폰 검사하기 AppFunction(FR-042)도 출시 빌드에서 뺀다
                check("PhoneCheckAppFunctionService" !in text) { "출시 빌드에 AppFunction 서비스가 있음 (app/src/release/AndroidManifest.xml 참고)" }
                // FCM 토큰은 연결을 시작할 때 처음 발급한다 (켜져 있으면 앱 시작 때 발급·전송)
                val autoInitOff = Regex("""android:name="firebase_messaging_auto_init_enabled"\s+android:value="false"""")
                check(autoInitOff.containsMatchIn(text)) { "FCM 자동 발급이 꺼져 있지 않음 (app/src/main/AndroidManifest.xml)" }
            }
        }
        // CI의 ./gradlew test와, 로컬에서 출시 APK를 만들 때(assembleRelease 등) 둘 다
        val name = variant.name.replaceFirstChar { it.uppercase() }
        tasks.matching { it.name in setOf("test", "assemble$name", "bundle$name") }.configureEach { dependsOn(verify) }
    }
}

dependencies {
    implementation(projects.feature.guardian.api)
    implementation(projects.feature.guardian.impl)
    implementation(projects.feature.onboarding.api)
    implementation(projects.feature.onboarding.impl)
    implementation(projects.feature.seniorAppdetail.api)
    implementation(projects.feature.seniorAppdetail.impl)
    implementation(projects.feature.seniorFamily.api)
    implementation(projects.feature.seniorFamily.impl)
    implementation(projects.feature.seniorHome.api)
    implementation(projects.feature.seniorHome.impl)
    implementation(projects.feature.seniorSites.api)
    implementation(projects.feature.seniorSites.impl)

    implementation(projects.core.data)
    implementation(projects.core.designsystem)
    implementation(projects.core.network)
    implementation(projects.core.notifications)
    implementation(projects.sync.work)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.work.runtime.ktx)

    // 푸시(FCM) 토큰 갱신 받기. 출시 빌드는 가족 연결이 꺼져 있고 인터넷 권한도 뺀다 (src/release/AndroidManifest.xml)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)

    testImplementation(libs.junit)

    debugImplementation(libs.androidx.compose.ui.test.manifest)
    // 시험용 App Check 확인(AppCheckProbeReceiver)이 Play Integrity 공급자를 직접 고른다
    debugImplementation(libs.firebase.appcheck.playintegrity)

    ksp(libs.androidx.hilt.compiler)
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)
    kspAndroidTest(libs.hilt.compiler)

    androidTestImplementation(projects.core.testing)
    androidTestImplementation(projects.core.dataTest)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.hilt.android.testing)
}
