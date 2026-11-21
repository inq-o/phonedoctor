plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "io.github.inq_o.phonedoctor"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass = "io.github.inq_o.phonedoctor.server.MainKt"
}

dependencies {
    implementation(platform(libs.ktor.bom))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)
    implementation(libs.hikari)
    implementation(libs.logback)
    implementation(libs.firebase.admin)
    implementation(libs.java.jwt)
    implementation(libs.jwks.rsa)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.embedded.postgres)
    testImplementation(platform(libs.embedded.postgres.binaries.bom))
    // 기본 포함은 x86용뿐이라 Apple Silicon 개발 기기용을 더함
    testRuntimeOnly(libs.embedded.postgres.binaries.darwin.arm64)
    testImplementation(libs.json.schema.validator)
}

tasks.test {
    useJUnitPlatform()
    // 응답을 API 계약(specs/001-mvp/contracts/openapi.yaml)과 대조한다
    val openapi = rootDir.resolve("../specs/001-mvp/contracts/openapi.yaml")
    inputs.file(openapi).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("openapi.path", openapi.absolutePath)
}
