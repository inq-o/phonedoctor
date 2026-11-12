plugins {
    alias(libs.plugins.phonedoctor.android.library)
    alias(libs.plugins.phonedoctor.hilt)
}

android {
    namespace = "io.github.inq_o.phonedoctor.core.notifications"
}

dependencies {
    api(projects.core.model)

    implementation(libs.androidx.core.ktx)
}
