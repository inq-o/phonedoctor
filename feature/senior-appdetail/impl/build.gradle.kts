plugins {
    alias(libs.plugins.phonedoctor.android.feature.impl)
    alias(libs.plugins.phonedoctor.android.library.compose)
}

android {
    namespace = "io.github.inq_o.phonedoctor.feature.seniorappdetail.impl"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.notifications)
    implementation(projects.feature.seniorAppdetail.api)

    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
