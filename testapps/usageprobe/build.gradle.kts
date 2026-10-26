plugins {
    alias(libs.plugins.phonedoctor.android.application)
}

android {
    namespace = "io.github.inq_o.phonedoctor.testapps.usageprobe"
    defaultConfig {
        applicationId = "io.github.inq_o.phonedoctor.testapps.usageprobe"
        versionCode = 1
        versionName = "0.1.0"
    }
}

dependencies {
    implementation(libs.androidx.work.runtime.ktx)
}
