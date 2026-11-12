plugins {
    alias(libs.plugins.phonedoctor.android.feature.impl)
    alias(libs.plugins.phonedoctor.android.library.compose)
}

android {
    namespace = "io.github.inq_o.phonedoctor.feature.seniorhome.impl"
}

dependencies {
    implementation(projects.core.data)
    implementation(projects.core.notifications)
    implementation(projects.feature.seniorHome.api)

    implementation(libs.androidx.activity.compose)

    testImplementation(libs.kotlinx.coroutines.test)
}
