package io.github.inq_o.phonedoctor.core.classifier.feature

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.Locale
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
        val label = koreanLabel(pm, info)
        return try {
            ManifestReader.read(File(info.sourceDir), label).takeIf {
                // 매니페스트의 package가 설치된 이름과 다르면(ZIP에 미끼 매니페스트를 넣은 경우 등) 판정하지 않는다:
                // 설치기가 읽은 것과 다른 매니페스트로 만든 벡터는 믿을 수 없다
                it.packageName == packageName
            }
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

    /**
     * PC(`extract.py` `korean_label`)처럼 한국어 라벨: 기기 언어와 상관없이 ko 설정으로 그 앱의 라벨 리소스를 읽는다
     * (ko 리소스가 없으면 시스템이 기본 라벨로 내려감). 리소스 없이 글자로 적힌 라벨은 그대로, 라벨이 없으면 빈 문자열
     */
    private fun koreanLabel(pm: PackageManager, info: ApplicationInfo): String {
        if (info.labelRes != 0) {
            try {
                val res = pm.getResourcesForApplication(info)
                val config = Configuration(res.configuration).apply { setLocale(Locale.KOREAN) }
                @Suppress("DEPRECATION")
                return Resources(res.assets, res.displayMetrics, config).getString(info.labelRes)
            } catch (_: PackageManager.NameNotFoundException) {
                // 아래로
            } catch (_: Resources.NotFoundException) {
                // 아래로
            }
        }
        return info.nonLocalizedLabel?.toString().orEmpty()
    }
}
