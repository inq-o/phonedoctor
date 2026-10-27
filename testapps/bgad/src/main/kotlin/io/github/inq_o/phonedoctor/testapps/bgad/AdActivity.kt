package io.github.inq_o.phonedoctor.testapps.bgad

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.Gravity
import android.widget.TextView

/** 전면 광고 흉내. holdSec 뒤 스스로 닫힌다. */
class AdActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(
            TextView(this).apply {
                text = "광고 (bgad ${intent.getStringExtra("mode")} ${intent.getIntExtra("seq", 0)})"
                textSize = 32f
                gravity = Gravity.CENTER
                setBackgroundColor(Color.rgb(255, 120, 0))
            },
        )
        window.decorView.postDelayed({ finish() }, intent.getIntExtra("holdSec", 5) * 1000L)
    }

    override fun onResume() {
        super.onResume()
        Log.i(AdReceiver.TAG, "RESUMED mode=${intent.getStringExtra("mode")} seq=${intent.getIntExtra("seq", 0)} t=${System.currentTimeMillis()}")
    }

    companion object {
        fun intent(context: Context, mode: String, seq: Int, holdSec: Int): Intent =
            Intent(context, AdActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra("mode", mode).putExtra("seq", seq).putExtra("holdSec", holdSec)
    }
}
