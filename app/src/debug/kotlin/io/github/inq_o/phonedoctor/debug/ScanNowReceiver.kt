package io.github.inq_o.phonedoctor.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import io.github.inq_o.phonedoctor.core.data.ScanScheduler

/**
 * 시험용 (debug 빌드 전용). 15분 주기를 기다리지 않고 검사를 한 번 돌린다.
 *
 * adb shell am broadcast -n io.github.inq_o.phonedoctor/.debug.ScanNowReceiver
 */
class ScanNowReceiver : BroadcastReceiver() {
    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Deps {
        fun scanScheduler(): ScanScheduler
    }

    override fun onReceive(context: Context, intent: Intent) {
        EntryPointAccessors.fromApplication(context.applicationContext, Deps::class.java).scanScheduler().scanNow("adb")
    }
}
