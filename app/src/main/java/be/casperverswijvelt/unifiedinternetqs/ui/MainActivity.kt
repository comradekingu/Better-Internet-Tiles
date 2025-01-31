package be.casperverswijvelt.unifiedinternetqs.ui

import android.os.Bundle
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private val contentViewId = 10101010

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val frame = FrameLayout(this)
        frame.id = contentViewId
        setContentView(
            frame,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        supportFragmentManager
            .beginTransaction()
            .add(contentViewId, SettingsFragment())
            .commit()
    }
}