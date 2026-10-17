package com.waytoconsistency

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

class HealthConnectRationaleActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (24 * resources.displayMetrics.density).toInt()
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad, pad, pad)
            addView(TextView(context).apply { text = "Health Connect privacy"; textSize = 26f })
            addView(TextView(context).apply {
                text = "Way to Consistency reads your steps, recent heart-rate samples, sleep duration, and exercise sessions only after you grant access. Finished runs can be written back when you choose. Health data stays on this device, is not sold, and is not included in social share cards. You can revoke access at any time in Health Connect settings."
                textSize = 17f
                setPadding(0, pad, 0, 0)
            })
        })
    }
}
