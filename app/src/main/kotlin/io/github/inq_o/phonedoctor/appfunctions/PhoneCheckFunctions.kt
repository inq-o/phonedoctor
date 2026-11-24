package io.github.inq_o.phonedoctor.appfunctions

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.appfunctions.AppFunctionDeclaration
import androidx.appfunctions.AppFunctionSerializable
import androidx.appfunctions.AppFunctionService
import androidx.appfunctions.AppFunctionServiceEntryPoint
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.data.SymptomRepository
import io.github.inq_o.phonedoctor.core.model.ScanResult
import io.github.inq_o.phonedoctor.sync.work.SymptomScanRunner
import kotlinx.coroutines.flow.first

/**
 * "휴대폰 검사하기"를 시스템 AI 에이전트(Gemini 등)가 부를 수 있게 내놓는다 (FR-042, Android 16+ AppFunctions).
 * 컴파일러가 이 클래스를 이어 [PhoneCheckAppFunctionService]와 함수 설명 XML(assets/phone_check.xml)을 만든다.
 *
 * 에이전트에게는 확인이 필요한 앱 **수**만 준다(앱 이름·횟수는 주지 않음): 에이전트가 기기 밖으로 보낼 수 있어서.
 * 출시 빌드는 꺼짐 (서비스를 출시 매니페스트에서 뺀다, 개인정보처리방침에 적기 전까지)
 */
@RequiresApi(Build.VERSION_CODES.BAKLAVA) // AppFunctions는 Android 16(API 36)부터. 시스템도 그 이상에서만 이 서비스를 부른다
@AppFunctionServiceEntryPoint(serviceName = "PhoneCheckAppFunctionService", appFunctionXmlFileName = "phone_check")
abstract class BasePhoneCheckAppFunctionService : AppFunctionService() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun runner(): SymptomScanRunner
        fun symptoms(): SymptomRepository
    }

    /**
     * 휴대폰에서 광고를 띄우는 앱이 있는지 바로 검사한다. 시니어 휴대폰을 지켜 주는 폰주치의 앱이 최근 사용 기록을 살펴본다.
     *
     * @return 검사 결과. 확인이 필요한 앱이 있으면 폰주치의 앱을 열어 보라고 안내한다.
     */
    @AppFunctionDeclaration(isDescribedByKDoc = true)
    suspend fun checkPhone(): PhoneCheckResult {
        val deps = EntryPointAccessors.fromApplication(applicationContext, Deps::class.java)
        return checkPhone(
            // 주기 검사와 같은 뒤처리(새 요청 알림·가족 동기화)까지. 보호자 폰이면 null
            scan = { deps.runner().run(TRIGGER)?.run?.result },
            openAlerts = { deps.symptoms().openAlerts.first().size },
        )
    }

    companion object {
        const val TRIGGER = "app_function"
    }
}

/**
 * 휴대폰 검사 결과.
 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class PhoneCheckResult(
    /** 이번에 검사를 마쳤는지. 사용 기록 접근 권한이 꺼져 있으면 false */
    val checked: Boolean,
    /** 지금 확인이 필요한 앱 수 */
    val appsToCheck: Int,
    /** 사용자에게 그대로 읽어 줄 수 있는 한 문장 */
    val message: String,
)

/** 검사하고 결과 문장을 만든다 (서비스 밖에서 시험하려고 떼어 냄) */
internal suspend fun checkPhone(scan: suspend () -> ScanResult?, openAlerts: suspend () -> Int): PhoneCheckResult {
    val result = scan() ?: return PhoneCheckResult(false, 0, "보호자 휴대폰에서는 검사하지 않아요.")
    val count = openAlerts()
    val checked = result == ScanResult.OK
    val message = when {
        result == ScanResult.NO_PERMISSION -> "폰주치의에 사용 기록 접근을 허용해야 검사할 수 있어요. 폰주치의 앱을 열어 주세요."
        result == ScanResult.LOCKED -> "휴대폰 잠금을 푼 뒤에 다시 검사해 주세요."
        !checked -> "지금은 검사하지 못했어요. 잠시 뒤 다시 해 주세요."
        count == 0 -> "광고를 띄우는 앱을 찾지 못했어요."
        else -> "확인이 필요한 앱이 ${count}개 있어요. 폰주치의 앱을 열어 확인해 주세요."
    }
    return PhoneCheckResult(checked, count, message)
}
