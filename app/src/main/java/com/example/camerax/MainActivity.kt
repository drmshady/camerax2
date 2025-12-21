package com.example.camerax

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.camerax.databinding.ActivityHomeBinding

/**
 * Home screen: routes to Capture / Calibration / Instructions.
 * Launcher activity (AndroidManifest.xml).
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Version label (robust: does not rely on BuildConfig/namespace).
        binding.versionText.text = buildVersionLabel()

        binding.captureCard.setOnClickListener {
            startActivity(Intent(this, CaptureActivity::class.java))
        }

        binding.calibrationCard.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        binding.instructionsCard.setOnClickListener {
            startActivity(Intent(this, InstructionsActivity::class.java))
        }
    }

    private fun buildVersionLabel(): String {
        return try {
            val pi = packageManager.getPackageInfo(packageName, 0)
            val vCode: Long = if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else {
                @Suppress("DEPRECATION")
                pi.versionCode.toLong()
            }
            val vName = pi.versionName ?: "—"
            "v$vName ($vCode)"
        } catch (_: Throwable) {
            "v—"
        }
    }
}
