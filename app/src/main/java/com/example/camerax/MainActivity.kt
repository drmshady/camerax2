package com.example.camerax

import android.content.Intent
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
}
