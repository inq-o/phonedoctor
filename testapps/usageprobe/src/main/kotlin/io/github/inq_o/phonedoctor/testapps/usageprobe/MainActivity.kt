package io.github.inq_o.phonedoctor.testapps.usageprobe

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * 장기 관측 초기 설정용 시작 화면. 실제 앱의 온보딩처럼 "앱을 한 번 연" 상태를 만들어
 * 대기 버킷을 NEVER에서 올린다. 이후 관측은 이 화면을 다시 열지 않고 백그라운드 작업으로 함.
 *
 * adb shell am start -n io.github.inq_o.phonedoctor.testapps.usageprobe/.MainActivity
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "usageprobe — 관측 준비됨" })
    }
}
