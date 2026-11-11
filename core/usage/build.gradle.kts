plugins {
    alias(libs.plugins.phonedoctor.android.library)
    alias(libs.plugins.phonedoctor.hilt)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.inq_o.phonedoctor.core.usage"
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.serialization.json)
}
