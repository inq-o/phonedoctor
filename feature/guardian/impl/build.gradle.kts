plugins {
    alias(libs.plugins.phonedoctor.android.feature.impl)
    alias(libs.plugins.phonedoctor.android.library.compose)
}

android {
    namespace = "io.github.inq_o.phonedoctor.feature.guardian.impl"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.network)
    implementation(projects.core.notifications)
    implementation(projects.feature.guardian.api)

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services.auth)
    implementation(libs.googleid)
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // ApiException 생성자의 body 타입
    testImplementation(libs.kotlinx.serialization.json)
}
