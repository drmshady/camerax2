package com.example.camerax

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.camerax.databinding.ActivityHomeBinding

/**
 * Home screen: routes to Capture / Calibration / Instructions.
 * Metadata (doctor/patient) is entered here to keep Capture screen camera-first.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    private val prefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Restore last values (doctor is usually stable; patient often changes).
        binding.homeDoctorNameEdit.setText(prefs.getString(KEY_DOCTOR, "") ?: "")
        binding.homePatientNameEdit.setText(prefs.getString(KEY_PATIENT_NAME, "") ?: "")
        binding.homePatientIdEdit.setText(prefs.getString(KEY_PATIENT_ID, "") ?: "")

        // Start capture from the button (and also when tapping the card header area).
        binding.startCaptureButton.setOnClickListener { startCapture() }
        binding.captureCard.setOnClickListener { startCapture() }

        binding.calibrationCard.setOnClickListener {
            startActivity(Intent(this, CalibrationActivity::class.java))
        }

        binding.instructionsCard.setOnClickListener {
            startActivity(Intent(this, InstructionsActivity::class.java))
        }
    }

    private fun startCapture() {
        val doctor = binding.homeDoctorNameEdit.text?.toString()?.trim().orEmpty()
        val patientName = binding.homePatientNameEdit.text?.toString()?.trim().orEmpty()
        val patientId = binding.homePatientIdEdit.text?.toString()?.trim().orEmpty()

        if (patientId.isBlank()) {
            binding.homePatientIdEdit.error = "Patient ID is required"
            binding.homePatientIdEdit.requestFocus()
            return
        }

        // Persist (fast + deterministic). You can clear patient fields manually if desired.
        prefs.edit()
            .putString(KEY_DOCTOR, doctor)
            .putString(KEY_PATIENT_NAME, patientName)
            .putString(KEY_PATIENT_ID, patientId)
            .apply()

        val i = Intent(this, CaptureActivity::class.java).apply {
            putExtra(CaptureActivity.EXTRA_DOCTOR_NAME, doctor)
            putExtra(CaptureActivity.EXTRA_PATIENT_NAME, patientName)
            putExtra(CaptureActivity.EXTRA_PATIENT_ID, patientId)
        }
        startActivity(i)
    }

    companion object {
        private const val PREFS_NAME = "photogrammetry_prefs"
        private const val KEY_DOCTOR = "doctor_name"
        private const val KEY_PATIENT_NAME = "patient_name"
        private const val KEY_PATIENT_ID = "patient_id"
    }
}
