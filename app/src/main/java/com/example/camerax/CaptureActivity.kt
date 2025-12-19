package com.example.camerax

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.camerax.databinding.ActivityCaptureBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CaptureActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCaptureBinding

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var boundCamera: Camera? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sessionManager: SessionManager
    private lateinit var manifestWriter: ManifestWriter
    private lateinit var zipExporter: ZipExporter
    private val sidecarWriter = ImageSidecarWriter()

    private val resultStore = CaptureResultStore()
    private var cameraController: CameraController? = null

    // Step 8 (stub)
    private val markerDetector: MarkerDetector = StubMarkerDetector()

    private val captureResolution = Size(1920, 1080)

    @Volatile private var isExporting: Boolean = false

    private var lastQualityStatus: QualityStatus = QualityStatus.OK
    private var lastQualityResult: QualityResult = QualityResult(
        status = QualityStatus.OK,
        blurScore = 0.0,
        overPercent = 0.0,
        underPercent = 0.0,
        distanceCm = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCaptureBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()

        // Back from Capture ALWAYS goes to Home
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val i = Intent(this@CaptureActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(i)
                finish()
            }
        })

        sessionManager = SessionManager(this)
        manifestWriter = ManifestWriter()
        zipExporter = ZipExporter(this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        binding.blockCaptureSwitch.setOnCheckedChangeListener { _, _ ->
            updateCaptureEnabled()
        }

        binding.torchSwitch.setOnCheckedChangeListener { _, isChecked ->
            val cam = boundCamera
            if (cam == null) {
                Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
                binding.torchSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            cam.cameraControl.enableTorch(isChecked)
        }

        binding.startSessionButton.setOnClickListener { startSessionFromUi() }

        binding.lockButton.setOnClickListener {
            cameraController?.lockForPhotogrammetry(settleMs = 1500L)
            updateLockStatusUi()
            binding.lockButton.postDelayed({ updateLockStatusUi() }, 2000L)
        }

        binding.captureButton.setOnClickListener { takePhoto() }

        binding.endSessionButton.setOnClickListener {
            sessionManager.endSession()
            updateUi()
            cameraController?.unlockAll()
            updateLockStatusUi()

            markerDetector.reset()
            updateMarkerUi()
        }

        binding.exportSessionButton.setOnClickListener { exportSession() }

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        updateUi()
        updateLockStatusUi()
        updateQualityUi(lastQualityResult)
        updateMarkerUi()
    }

    private fun updateMarkerUi() {
        binding.markersText.text = markerDetector.latest().displayText
    }

    private fun startSessionFromUi() {
        val doctor = binding.doctorNameEdit.text?.toString()?.trim().orEmpty()
        val patientName = binding.patientNameEdit.text?.toString()?.trim().orEmpty()
        val patientId = binding.patientIdEdit.text?.toString()?.trim().orEmpty()

        if (patientId.isBlank()) {
            Toast.makeText(this, "Enter Patient ID", Toast.LENGTH_SHORT).show()
            return
        }

        sessionManager.startSession(
            SessionMeta(
                type = SessionType.CAPTURE,
                doctorName = doctor.ifBlank { null },
                patientName = patientName.ifBlank { null },
                patientId = patientId
            )
        )

        writeManifest()
        updateUi()
    }

    private fun takePhoto() {
        if (!sessionManager.isSessionActive()) {
            Toast.makeText(this, "Start a session first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (binding.blockCaptureSwitch.isChecked && lastQualityStatus != QualityStatus.OK) {
            Toast.makeText(this, "Image quality not OK: $lastQualityStatus", Toast.LENGTH_SHORT).show()
            return
        }

        val imageCapture = imageCapture ?: return
        val photoFile = sessionManager.getNextImageFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        val tsMs = System.currentTimeMillis()
        val cap = resultStore.snapshot()
        val q = lastQualityResult

        val exposureFlags = mutableListOf<String>().apply {
            if (q.status == QualityStatus.OVER) add("OVER")
            if (q.status == QualityStatus.UNDER) add("UNDER")
        }

        val meta = ImageSidecarMetadata(
            timestampMs = tsMs,
            filename = photoFile.name,
            iso = cap.iso,
            shutterNs = cap.shutterNs,
            focusDistanceDiopters = cap.focusDistanceDiopters,
            distanceEstimateCm = q.distanceCm,
            blurScore = q.blurScore,
            exposureFlags = exposureFlags,
            qualityStatus = q.status.name,
            lockModeUsed = cameraController?.lockModeUsed ?: "fallback",
            torchState = if (binding.torchSwitch.isChecked) "ON" else "OFF",
            sessionType = SessionType.CAPTURE.name,
            sessionName = sessionManager.getSessionName(),
            doctorName = sessionManager.getSessionMeta()?.doctorName,
            patientName = sessionManager.getSessionMeta()?.patientName,
            patientId = sessionManager.getSessionMeta()?.patientId
        )

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@CaptureActivity, "Saved: ${photoFile.name}", Toast.LENGTH_SHORT).show()

                    cameraExecutor.execute {
                        sidecarWriter.writeSidecarJson(photoFile, meta)
                    }

                    writeManifest()
                }
            }
        )
    }

    private fun exportSession() {
        if (!sessionManager.hasSession()) {
            Toast.makeText(this, "No session to export.", Toast.LENGTH_SHORT).show()
            return
        }
        if (isExporting) return

        val sessionDir = sessionManager.getSessionDirectory()
        isExporting = true
        updateExportEnabled()

        cameraExecutor.execute {
            try {
                val zipFile = zipExporter.exportSessionToZip(sessionDir, sessionManager.getSessionType())
                runOnUiThread {
                    Toast.makeText(this, "Exported: ${zipFile.absolutePath}", Toast.LENGTH_LONG).show()
                }
            } catch (t: Throwable) {
                Log.e(TAG, "Export failed: ${t.message}", t)
                runOnUiThread {
                    Toast.makeText(this, "Export failed: ${t.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                isExporting = false
                runOnUiThread { updateExportEnabled() }
            }
        }
    }

    private fun writeManifest() {
        val sessionInfo = sessionManager.getSessionInfo().toMutableMap()
        sessionInfo["chosenResolution"] = "${captureResolution.width}x${captureResolution.height}"
        manifestWriter.writeManifest(sessionInfo, sessionManager.getSessionDirectory())
    }

    private fun updateUi() {
        val active = sessionManager.isSessionActive()

        binding.setupCard.visibility = if (active) android.view.View.GONE else android.view.View.VISIBLE

        binding.startSessionButton.isEnabled = !active
        binding.lockButton.isEnabled = active
        binding.captureButton.isEnabled = active
        binding.endSessionButton.isEnabled = active

        updateCaptureEnabled()
        updateExportEnabled()
    }

    private fun updateCaptureEnabled() {
        val active = sessionManager.isSessionActive()
        val block = binding.blockCaptureSwitch.isChecked
        val ok = lastQualityStatus == QualityStatus.OK
        binding.captureButton.isEnabled = active && (!block || ok)
    }

    private fun updateExportEnabled() {
        binding.exportSessionButton.isEnabled = sessionManager.hasSession() && !isExporting
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder)
                .setSessionCaptureCallback(resultStore.sessionCallback)

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(binding.viewFinder.surfaceProvider)
            }

            val imageCaptureBuilder = ImageCapture.Builder()
                .setTargetResolution(captureResolution)
            Camera2Interop.Extender(imageCaptureBuilder)
                .setSessionCaptureCallback(resultStore.sessionCallback)
            imageCapture = imageCaptureBuilder.build()

            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(
                cameraExecutor,
                QualityAnalyzer(
                    resultStore = resultStore,
                    targetFps = 12,
                    onResult = { result -> runOnUiThread { updateQualityUi(result) } }
                )
            )
            imageAnalysis = analysis

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            try {
                cameraProvider.unbindAll()
                val camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageCapture,
                    imageAnalysis
                )
                boundCamera = camera

                val cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
                val cameraId = Camera2CameraInfo.from(camera.cameraInfo).cameraId
                val characteristics = cameraManager.getCameraCharacteristics(cameraId)

                cameraController = CameraController(
                    camera = camera,
                    characteristics = characteristics,
                    resultStore = resultStore,
                    coroutineScope = lifecycleScope,
                    executor = cameraExecutor
                )

                updateCapabilityUi(characteristics)

            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateCapabilityUi(characteristics: CameraCharacteristics) {
        val caps = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toSet().orEmpty()
        val hasManualSensor = caps.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR)

        val minFocusDist = characteristics.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        val hasManualFocusDist = minFocusDist > 0f

        val aeLockAvailable = characteristics.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE) == true
        val awbLockAvailable = characteristics.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE) == true

        binding.manualSensorSupport.text =
            "Manual Sensor: ${if (hasManualSensor) "Supported" else "No"} | AE lock: ${if (aeLockAvailable) "Yes" else "No"}"
        binding.manualFocusSupport.text =
            "Manual Focus Distance: ${if (hasManualFocusDist) "Supported" else "No"}"
        binding.manualWhiteBalanceSupport.text =
            "AWB Lock: ${if (awbLockAvailable) "Supported" else "No"}"
    }

    private fun updateLockStatusUi() {
        binding.afLockStatus.text = "AF: ${cameraController?.afStatus ?: "—"}"
        binding.aeLockStatus.text = "AE: ${cameraController?.aeStatus ?: "—"}"
        binding.awbLockStatus.text = "WB: ${cameraController?.wbStatus ?: "—"}"
    }

    private fun updateQualityUi(result: QualityResult) {
        lastQualityResult = result
        lastQualityStatus = result.status

        binding.qualityStatusText.text = when (result.status) {
            QualityStatus.OK -> "✅ OK"
            QualityStatus.BLUR -> "⚠️ BLUR"
            QualityStatus.OVER -> "⚠️ OVER"
            QualityStatus.UNDER -> if (!binding.torchSwitch.isChecked) "⚠️ UNDER (Torch?)" else "⚠️ UNDER"
        }

        binding.distanceText.text =
            result.distanceCm?.let { "Distance: ~${it.toInt()} cm" } ?: "Distance: N/A"

        // Step 8 stub (N/A for now)
        updateMarkerUi()

        updateCaptureEnabled()
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) startCamera()
            else {
                Toast.makeText(this, "Permissions not granted.", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { boundCamera?.cameraControl?.enableTorch(false) } catch (_: Throwable) {}
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "CaptureActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10

        private val REQUIRED_PERMISSIONS = mutableListOf(
            Manifest.permission.CAMERA
        ).apply {
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
                add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        }.toTypedArray()
    }
}
