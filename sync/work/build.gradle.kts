plugins {
    alias(libs.plugins.phonedoctor.android.library)
    alias(libs.plugins.phonedoctor.hilt)
}

android {
    namespace = "io.github.inq_o.phonedoctor.sync.work"
}

dependencies {
    ksp(libs.androidx.hilt.compiler)

    implementation(projects.core.data)
    implementation(projects.core.notifications)
    implementation(libs.androidx.hilt.work)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.kotlinx.coroutines.android)
}
