package io.github.inq_o.phonedoctor.testapps.bgad

import android.app.Activity
import android.os.Bundle

/**
 * 광고 일정만 걸고 바로 닫힌다. 이후 화면은 모두 배경(알람)에서 뜬다.
 *
 * adb shell am start -n io.github.inq_o.phonedoctor.testapps.bgad/.MainActivity \
 *   --es mode saw|fsi|overlay --ei count 10 --ei intervalSec 60 --ei holdSec 5
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val e = intent.extras
        AdReceiver.schedule(
            this,
            mode = e?.getString("mode") ?: "saw",
            left = e?.getInt("count", 10) ?: 10,
            intervalSec = e?.getInt("intervalSec", 60) ?: 60,
            holdSec = e?.getInt("holdSec", 5) ?: 5,
            seq = 0,
        )
        finish()
    }
}
