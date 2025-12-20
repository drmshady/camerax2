package com.example.camerax

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.isActive
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
import com.example.camerax.databinding.ActivityCalibrationBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.abs

class CalibrationActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCalibrationBinding

    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var boundCamera: Camera? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sessionManager: SessionManager
    private lateinit var manifestWriter: ManifestWriter
    private lateinit var zipExporter: ZipExporter

    // ===== Phase 2: PC Transfer (LAN upload) =====
    private lateinit var transferConfigStore: TransferConfigStore
    private lateinit var sessionUploader: SessionUploader
    private var uploadJob: Job? = null
    @Volatile private var isUploading: Boolean = false
    private var lastUploadPercent: Int = -1
    private var lastUploadUiUpdateMs: Long = 0L
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
        binding = ActivityCalibrationBinding.inflate(layoutInflater)
        setContentView(binding.root)

        supportActionBar?.hide()

        // Back from Calibration ALWAYS goes to Home
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val i = Intent(this@CalibrationActivity, MainActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                }
                startActivity(i)
                finish()
            }
        })

        sessionManager = SessionManager(this)
        manifestWriter = ManifestWriter()
        zipExporter = ZipExporter(this)
        transferConfigStore = TransferConfigStore(this)
        sessionUploader = SessionUploader(this)
        cameraExecutor = Executors.newSingleThreadExecutor()
        initTransferUi()

        initAdvancedPanelUi()
        // Default calibration target distance = 25 cm (only on first open)
        if (savedInstanceState == null) {
            val current = binding.targetDistanceEdit.text?.toString()?.trim().orEmpty()
            if (current.isBlank()) {
                binding.targetDistanceEdit.setText("25")
                binding.targetDistanceEdit.setSelection(binding.targetDistanceEdit.text?.length ?: 0)
            }
        }

        binding.instructionsButton.setOnClickListener {
            startActivity(Intent(this, InstructionsActivity::class.java))
        }

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

        binding.sendSessionButton.setOnClickListener { startUpload() }
        binding.cancelSendButton.setOnClickListener { cancelUpload() }

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)

        updateUi()
        updateLockStatusUi()
        updateQualityUi(lastQualityResult)
        updateCalibrationHint()
        updateMarkerUi()
    }

    private fun updateMarkerUi() {
        binding.markersText.text = markerDetector.latest().displayText
    }

    private fun startSessionFromUi() {
        val target = binding.targetDistanceEdit.text?.toString()?.trim().orEmpty()
        val targetCm = target.toIntOrNull()
        if (targetCm == null || targetCm <= 0) {
            Toast.makeText(this, "Enter target distance (cm)", Toast.LENGTH_SHORT).show()
            return
        }

        sessionManager.startSession(
            SessionMeta(
                type = SessionType.CALIBRATION,
                calibrationTargetDistanceCm = targetCm
            )
        )

        writeManifest()
        updateUi()
        updateCalibrationHint()
    }

    private fun takePhoto() {
        if (!sessionManager.isSessionActive()) {
            Toast.makeText(this, "Start calibration session first.", Toast.LENGTH_SHORT).show()
            return
        }

        if (binding.blockCaptureSwitch.isChecked && lastQualityStatus != QualityStatus.OK) {
            Toast.makeText(this, "Quality not OK: $lastQualityStatus", Toast.LENGTH_SHORT).show()
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
            sessionType = SessionType.CALIBRATION.name,
            sessionName = sessionManager.getSessionName(),
            calibrationTargetDistanceCm = sessionManager.getSessionMeta()?.calibrationTargetDistanceCm
        )

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(this@CalibrationActivity, "Saved: ${photoFile.name}", Toast.LENGTH_SHORT).show()

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

    // ===== Phase 2: PC Transfer helpers =====
    private fun initAdvancedPanelUi() {
        // Advanced panel is collapsed by default to keep PreviewView visible.
        binding.advancedContainer.visibility = View.GONE
        binding.advancedToggleButton.text = "Advanced ▾"
        binding.advancedToggleButton.setOnClickListener {
            val showing = binding.advancedContainer.visibility == View.VISIBLE
            binding.advancedContainer.visibility = if (showing) View.GONE else View.VISIBLE
            binding.advancedToggleButton.text = if (showing) "Advanced ▾" else "Advanced ▴"
        }
    }



    private fun initTransferUi() {
        binding.sendProgressBar.max = 100
        binding.sendProgressBar.progress = 0
        binding.sendProgressBar.visibility = View.GONE
        binding.cancelSendButton.visibility = View.GONE

        binding.pcIpEdit.setText(transferConfigStore.getPcIp())
        binding.pcPortEdit.setText(transferConfigStore.getPcPort().toString())
        binding.sendStatusText.text = "Ready"
    }

    private fun persistTransferInputs(): Pair<String, Int>? {
        val ip = binding.pcIpEdit.text?.toString()?.trim().orEmpty()
        val portStr = binding.pcPortEdit.text?.toString()?.trim().orEmpty()

        if (ip.isBlank()) {
            Toast.makeText(this, "Enter PC IP", Toast.LENGTH_SHORT).show()
            return null
        }

        val port = portStr.toIntOrNull() ?: 8080
        if (port !in 1..65535) {
            Toast.makeText(this, "Invalid port", Toast.LENGTH_SHORT).show()
            return null
        }

        transferConfigStore.setPcIp(ip)
        transferConfigStore.setPcPort(port)
        return ip to port
    }

    private fun isWifiConnected(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val net = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(net) ?: return false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } catch (_: Throwable) {
            // Some devices/ROMs may throw without ACCESS_NETWORK_STATE; don't crash.
            true
        }
    }

    private fun startUpload() {
        try {
        if (isUploading) return
        if (!sessionManager.hasSession()) {
            Toast.makeText(this, "No session to send.", Toast.LENGTH_SHORT).show()
            return
        }
        if (sessionManager.isSessionActive()) {
            Toast.makeText(this, "End the session before sending.", Toast.LENGTH_SHORT).show()
            return
        }
        val cfg = persistTransferInputs() ?: return
        if (!isWifiConnected()) {
            Toast.makeText(this, "Wi‑Fi not connected.", Toast.LENGTH_SHORT).show()
            return
        }

        val (ip, port) = cfg
        val url = "http://$ip:$port/upload"

        val sessionDir = sessionManager.getSessionDirectory()
        val sessionType = sessionManager.getSessionType()

        isUploading = true
        lastUploadPercent = -1
        lastUploadUiUpdateMs = 0L
        binding.sendProgressBar.progress = 0
        binding.sendProgressBar.visibility = View.VISIBLE
        binding.cancelSendButton.visibility = View.VISIBLE
        binding.sendStatusText.text = "Preparing ZIP…"
        updateUi()

        uploadJob = lifecycleScope.launch {
            try {
                val zipFile = withContext(Dispatchers.IO) {
                    zipExporter.exportSessionToZip(sessionDir, sessionType)
                }
                val totalBytes = zipFile.length()

                binding.sendStatusText.text = "Hashing…"
                val sha256 = withContext(Dispatchers.IO) {
                    // Cancellation-safe: computeSha256 polls shouldContinue() to abort early.
                    SessionUploader.computeSha256(zipFile) { isActive }
                }

                val meta = SessionUploader.UploadMeta(
                    sessionType = sessionType.name,
                    sessionName = sessionDir.name,
                    zipFileName = zipFile.name,
                    fileSize = totalBytes,
                    sha256 = sha256
                )

                binding.sendStatusText.text = "Uploading…"
                val result = sessionUploader.upload(
                    url = url,
                    zipFile = zipFile,
                    meta = meta
                ) { sent, total ->
                    val pct = if (total > 0L) ((sent * 100L) / total).toInt() else 0
                    val now = System.currentTimeMillis()
                    if (pct != lastUploadPercent && (now - lastUploadUiUpdateMs) > 120L) {
                        lastUploadPercent = pct
                        lastUploadUiUpdateMs = now
                        runOnUiThread {
                            binding.sendProgressBar.progress = pct.coerceIn(0, 100)
                            binding.sendStatusText.text = "Uploading… $pct%"
                        }
                    }
                }

                if (result.success) {
                    withContext(Dispatchers.IO) {
                        SessionUploader.markSessionSent(
                            context = this@CalibrationActivity,
                            meta = meta,
                            url = url,
                            response = result.responseBody,
                            httpCode = result.httpCode
                        )
                    }
                    Toast.makeText(this@CalibrationActivity, "Sent ✓ (${result.httpCode})", Toast.LENGTH_LONG).show()
                    binding.sendStatusText.text = "Sent ✓ (${result.httpCode})"
                } else {
                    Toast.makeText(this@CalibrationActivity, "Send failed: ${result.errorMessage}", Toast.LENGTH_LONG).show()
                    binding.sendStatusText.text = "Failed: ${result.errorMessage}"
                }
            } catch (ce: CancellationException) {
                binding.sendStatusText.text = "Canceled"
            } catch (t: Throwable) {
                Log.e(TAG, "Upload failed: ${t.message}", t)
                binding.sendStatusText.text = "Error: ${t.message}"
                Toast.makeText(this@CalibrationActivity, "Upload error: ${t.message}", Toast.LENGTH_LONG).show()
            } finally {
                isUploading = false
                binding.cancelSendButton.visibility = View.GONE
                updateUi()
            }
        }
    
        } catch (t: Throwable) {
            Log.e(TAG, "startUpload crashed: ${t.message}", t)
            Toast.makeText(this, "Send crashed: ${t.message}", Toast.LENGTH_LONG).show()
            isUploading = false
            binding.cancelSendButton.visibility = View.GONE
            binding.sendProgressBar.visibility = View.GONE
            binding.sendStatusText.text = "Error: ${t.message}"
            updateUi()
        }
}

    private fun cancelUpload() {
        uploadJob?.cancel()
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
        updateTransferEnabled()
    }

    private fun updateTransferEnabled() {
        // Require a completed (ended) session for deterministic transfer
        val canSend = sessionManager.hasSession() && !sessionManager.isSessionActive() && !isExporting && !isUploading
        binding.sendSessionButton.isEnabled = canSend
        binding.pcIpEdit.isEnabled = !isUploading
        binding.pcPortEdit.isEnabled = !isUploading
        binding.cancelSendButton.isEnabled = isUploading

        if (!isUploading) {
            binding.sendProgressBar.visibility = View.GONE
            binding.cancelSendButton.visibility = View.GONE
        }
    }

    private fun updateCaptureEnabled() {
        val active = sessionManager.isSessionActive()
        val block = binding.blockCaptureSwitch.isChecked
        val ok = lastQualityStatus == QualityStatus.OK
        binding.captureButton.isEnabled = active && (!block || ok)
    }

    private fun updateExportEnabled() {
        binding.exportSessionButton.isEnabled = sessionManager.hasSession() && !isExporting && !isUploading
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
        updateCalibrationHint()
    }

    private fun updateCalibrationHint() {
        val targetCm = sessionManager.getSessionMeta()?.calibrationTargetDistanceCm
            ?: binding.targetDistanceEdit.text?.toString()?.trim()?.toIntOrNull()

        val current = lastQualityResult.distanceCm
        if (targetCm == null) {
            binding.calibrationHintText.text = "Set target distance (cm) to begin."
            return
        }

        if (current == null) {
            binding.calibrationHintText.text = "Target $targetCm cm | Current N/A"
            return
        }

        val diff = current - targetCm
        val msg = when {
            abs(diff) <= 2 -> "Target $targetCm cm | Current ~${current.toInt()} cm ✅"
            diff > 0 -> "Target $targetCm cm | Current ~${current.toInt()} cm → Move closer"
            else -> "Target $targetCm cm | Current ~${current.toInt()} cm → Move farther"
        }

        binding.calibrationHintText.text = msg
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
        private const val TAG = "CalibrationActivity"
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
