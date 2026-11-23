package io.github.inq_o.phonedoctor.core.classifier.feature

import android.content.Context
import android.content.pm.PackageManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 설치된 앱의 특징 벡터 (T023). base.apk 매니페스트를 직접 읽고, 라벨만 PackageManager에서 받는다(시스템 로케일).
 * 읽을 수 없는 앱(지워짐·깨진 매니페스트)은 null — 판정하지 않고 넘어간다
 */
@Singleton
class AppFeatureExtractor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val schema: FeatureSchema by lazy {
        context.assets.open(FeatureSchema.ASSET).bufferedReader().use { FeatureSchema.parse(it.readText()) }
    }

    val schemaVersion: Int get() = schema.version

    fun raw(packageName: String): RawFeatures? {
        val pm = context.packageManager
        val info = try {
            pm.getApplicationInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        }
        val label = pm.getApplicationLabel(info).toString()
        return try {
            ManifestReader.read(File(info.sourceDir), label)
        } catch (_: ManifestException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            // sourceDir이 비었거나(지워지는 중) 예상 못 한 구조: 판정하지 않고 넘어간다 (남의 앱 파일로 우리 앱이 죽지 않게)
            null
        }
    }

    fun extract(packageName: String): FeatureVector? = raw(packageName)?.let { Vectorizer.vectorize(it, schema) }
}
