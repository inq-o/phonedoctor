plugins {
    alias(libs.plugins.phonedoctor.android.feature.impl)
    alias(libs.plugins.phonedoctor.android.library.compose)
}

android {
    namespace = "io.github.inq_o.phonedoctor.feature.seniorfamily.impl"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(projects.core.notifications)
    implementation(projects.feature.seniorFamily.api)

    implementation(libs.androidx.activity.compose)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ApiException 생성자의 body 타입
    testImplementation(libs.kotlinx.serialization.json)
}
