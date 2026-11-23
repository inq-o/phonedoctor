package io.github.inq_o.phonedoctor.feature.guardian.impl

import android.annotation.SuppressLint
import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialUnsupportedException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import io.github.inq_o.phonedoctor.core.data.RoleMismatchException
import java.io.IOException

/** 이 빌드에 Google 로그인 설정(웹 클라이언트 ID)이 없거나, 받은 자격 증명이 Google ID 토큰이 아님 */
internal class GoogleSignInUnavailableException : IllegalStateException("Google 로그인을 쓸 수 없음")

/**
 * [Google로 로그인] 버튼 흐름 (Credential Manager). 계정이 없으면 Google 화면에서 추가할 수 있다.
 * [activity]는 화면의 Activity (계정 고르는 창을 그 위에 띄움)
 */
internal suspend fun requestGoogleIdToken(activity: Context): String {
    val clientId = webClientId(activity) ?: throw GoogleSignInUnavailableException()
    val request = GetCredentialRequest.Builder()
        .addCredentialOption(GetSignInWithGoogleOption.Builder(clientId).build())
        .build()
    val credential = CredentialManager.create(activity).getCredential(activity, request).credential
    if (credential is CustomCredential && credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
        return GoogleIdTokenCredential.createFrom(credential.data).idToken
    }
    throw GoogleSignInUnavailableException()
}

/** Firebase 설정(google-services.json)에서 만들어지는 웹 클라이언트 ID. 그 파일 없이 만든 빌드(CI)에는 없다 */
@SuppressLint("DiscouragedApi")
private fun webClientId(context: Context): String? {
    val id = context.resources.getIdentifier("default_web_client_id", "string", context.packageName)
    return if (id == 0) null else context.getString(id)
}

enum class SignInError { NO_ACCOUNT, UNAVAILABLE, NETWORK, WRONG_ROLE, UNKNOWN }

/** 사용자가 계정 고르기를 닫았으면 null (안내 없이 처음 화면으로) */
internal fun Throwable.toSignInError(): SignInError? = when (this) {
    is GetCredentialCancellationException -> null
    is NoCredentialException -> SignInError.NO_ACCOUNT
    is GetCredentialProviderConfigurationException, is GetCredentialUnsupportedException, is GoogleSignInUnavailableException -> SignInError.UNAVAILABLE
    is IOException -> SignInError.NETWORK
    is RoleMismatchException -> SignInError.WRONG_ROLE
    else -> SignInError.UNKNOWN
}
