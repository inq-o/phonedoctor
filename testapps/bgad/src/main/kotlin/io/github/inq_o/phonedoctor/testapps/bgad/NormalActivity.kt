package io.github.inq_o.phonedoctor.testapps.bgad

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout

/** 정상 전환 수집(E2)용 화면: 앱 안 전면 광고(R6, 앱 밖 광고 아님)와 사용자가 누른 다른 앱 열기 */
class NormalActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 400, 0, 0) // 상태 표시줄 아래로 (edge-to-edge)
                addView(Button(context).apply {
                    text = "앱 안 광고"
                    setOnClickListener { startActivity(AdActivity.intent(context, "inapp", 0, 3)) }
                })
                addView(Button(context).apply {
                    text = "설정 열기"
                    setOnClickListener { startActivity(Intent(Settings.ACTION_SETTINGS)) }
                })
            },
        )
    }
}
