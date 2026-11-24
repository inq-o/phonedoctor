plugins {
    alias(libs.plugins.phonedoctor.android.library)
    alias(libs.plugins.phonedoctor.hilt)
}

android {
    namespace = "io.github.inq_o.phonedoctor.core.classifier"
}

/** 특징 스키마를 앱 asset으로 복사한다. PC와 같은 파일을 읽게 (classifier.md §2) */
abstract class CopyFeatureSchema : DefaultTask() {
    @get:InputFile
    abstract val schema: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        out.mkdirs()
        schema.get().asFile.copyTo(File(out, schema.get().asFile.name))
    }
}

val copyFeatureSchema = tasks.register<CopyFeatureSchema>("copyFeatureSchema") {
    schema.set(rootProject.layout.projectDirectory.file("ml/schema/feature_schema_v1.json"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(copyFeatureSchema, CopyFeatureSchema::outputDir)
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlinx.serialization.json)
}
