package io.github.inq_o.phonedoctor.core.network

import com.google.firebase.appcheck.AppCheckProviderFactory
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory

/** 출시 빌드: Play Integrity (Google Play 밖 배포 설정, ADR-0001 보완) */
internal fun appCheckProviderFactory(): AppCheckProviderFactory = PlayIntegrityAppCheckProviderFactory.getInstance()
