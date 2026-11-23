package io.github.inq_o.phonedoctor.core.network

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.debug.DebugAppCheckProviderFactory

/**
 * 디버그 빌드: 에뮬레이터처럼 Play Integrity를 통과하지 못하는 기기에서도 서버를 부를 수 있게 디버그 공급자.
 * 처음 실행하면 logcat `DebugAppCheckProvider` 태그에 디버그 비밀값이 찍힌다. Firebase 콘솔 App Check > 앱 > 디버그 토큰 관리에 등록해야 통과한다
 */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = DebugAppCheckProviderFactory.getInstance()
