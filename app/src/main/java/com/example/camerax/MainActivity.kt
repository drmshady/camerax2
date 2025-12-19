package com.example.camerax

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.util.Size
import android.widget.Toast
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
import com.example.camerax.databinding.ActivityMainBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var viewBinding: ActivityMainBinding
    private var imageCapture: ImageCapture? = null
    private var imageAnalysis: ImageAnalysis? = null

    private lateinit var cameraExecutor: ExecutorService
    private lateinit var sessionManager: SessionManager
    private lateinit var manifestWriter: ManifestWriter
    private var cameraController: CameraController? = null

    private val captureResolution = Size(1920, 1080)

    // Holds latest AUTO ISO/shutter/focus via Camera2 callback
    private val resultStore = CaptureResultStore()

    // Torch control
    private var boundCamera: Camera? = null

    // Step 5 gating state
    private var lastQualityStatus: QualityStatus = QualityStatus.OK

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(viewBinding.root)

        sessionManager = SessionManager(this)
        manifestWriter = ManifestWriter()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Switch affects capture enablement
        viewBinding.blockCaptureSwitch.setOnCheckedChangeListener { _, _ ->
            updateCaptureEnabled()
        }

        // Torch switch
        viewBinding.torchSwitch.setOnCheckedChangeListener { _, isChecked ->
            val cam = boundCamera
            if (cam == null) {
                Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
                viewBinding.torchSwitch.isChecked = false
                return@setOnCheckedChangeListener
            }
            cam.cameraControl.enableTorch(isChecked)
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        viewBinding.startSessionButton.setOnClickListener {
            sessionManager.startSession()
            updateUi()
            writeManifest()
        }

        viewBinding.endSessionButton.setOnClickListener {
            sessionManager.endSession()
            updateUi()
            cameraController?.unlockAll()
            updateLockStatusUi()
        }

        viewBinding.captureButton.setOnClickListener { takePhoto() }

        viewBinding.lockButton.setOnClickListener {
            cameraController?.lockForPhotogrammetry(settleMs = 1500L)
            updateLockStatusUi()
            // update again after settle+lock completes
            viewBinding.lockButton.postDelayed({ updateLockStatusUi() }, 2000L)
        }

        updateUi()
        updateLockStatusUi()
        updateQualityUi(
            QualityResult(
                status = QualityStatus.OK,
                blurScore = 0.0,
                overPercent = 0.0,
                underPercent = 0.0,
                distanceCm = null
            )
        )
    }

    private fun takePhoto() {
        if (!sessionManager.isSessionActive()) {
            Toast.makeText(this, "Please start a session first.", Toast.LENGTH_SHORT).show()
            return
        }

        // Step 5: block capture unless OK (if enabled)
        if (viewBinding.blockCaptureSwitch.isChecked && lastQualityStatus != QualityStatus.OK) {
            Toast.makeText(this, "Image quality not OK: $lastQualityStatus", Toast.LENGTH_SHORT).show()
            return
        }

        val imageCapture = imageCapture ?: return
        val photoFile = sessionManager.getNextImageFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onError(exc: ImageCaptureException) {
                    Log.e(TAG, "Photo capture failed: ${exc.message}", exc)
                }

                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Toast.makeText(baseContext, "Saved: ${photoFile.absolutePath}", Toast.LENGTH_SHORT).show()
                    Log.d(TAG, "Saved: ${photoFile.absolutePath}")
                    writeManifest()
                }
            }
        )
    }

    private fun writeManifest() {
        val sessionInfo = sessionManager.getSessionInfo().toMutableMap()
        sessionInfo["chosenResolution"] = "${captureResolution.width}x${captureResolution.height}"
        manifestWriter.writeManifest(sessionInfo, sessionManager.getSessionDirectory())
    }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            // Preview + Camera2 session callback (for resultStore)
            val previewBuilder = Preview.Builder()
            Camera2Interop.Extender(previewBuilder)
                .setSessionCaptureCallback(resultStore.sessionCallback)

            val preview = previewBuilder.build().also {
                it.setSurfaceProvider(viewBinding.viewFinder.surfaceProvider)
            }

            // ImageCapture + Camera2 session callback (keeps callback alive during capture)
            val imageCaptureBuilder = ImageCapture.Builder()
                .setTargetResolution(captureResolution)

            Camera2Interop.Extender(imageCaptureBuilder)
                .setSessionCaptureCallback(resultStore.sessionCallback)

            imageCapture = imageCaptureBuilder.build()

            // ImageAnalysis (Step 5)
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analysis.setAnalyzer(
                cameraExecutor,
                QualityAnalyzer(
                    resultStore = resultStore,
                    targetFps = 12,
                    onResult = { result ->
                        runOnUiThread { updateQualityUi(result) }
                    }
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

        viewBinding.manualSensorSupport.text =
            "Manual Sensor: ${if (hasManualSensor) "Supported" else "Not Supported"} | AE lock: ${if (aeLockAvailable) "Yes" else "No"}"
        viewBinding.manualFocusSupport.text =
            "Manual Focus Distance: ${if (hasManualFocusDist) "Supported" else "Not Supported"}"
        viewBinding.manualWhiteBalanceSupport.text =
            "AWB Lock: ${if (awbLockAvailable) "Supported" else "Not Supported"}"
    }

    private fun updateLockStatusUi() {
        viewBinding.afLockStatus.text = "AF: ${cameraController?.afStatus ?: "—"}"
        viewBinding.aeLockStatus.text = "AE: ${cameraController?.aeStatus ?: "—"}"
        viewBinding.awbLockStatus.text = "WB: ${cameraController?.wbStatus ?: "—"}"
    }

    private fun updateQualityUi(result: QualityResult) {
        lastQualityStatus = result.status

        viewBinding.qualityStatusText.text = when (result.status) {
            QualityStatus.OK -> "✅ OK"
            QualityStatus.BLUR -> "⚠️ BLUR"
            QualityStatus.OVER -> "⚠️ OVER"
            QualityStatus.UNDER ->
                if (!viewBinding.torchSwitch.isChecked) "⚠️ UNDER (try Torch)" else "⚠️ UNDER"
        }

        viewBinding.distanceText.text =
            result.distanceCm?.let { "Distance: ~${it.toInt()} cm" } ?: "Distance: N/A"

        updateCaptureEnabled()
    }

    private fun updateUi() {
        val active = sessionManager.isSessionActive()
        viewBinding.startSessionButton.isEnabled = !active
        viewBinding.endSessionButton.isEnabled = active
        viewBinding.lockButton.isEnabled = active
        updateCaptureEnabled()
    }

    private fun updateCaptureEnabled() {
        val active = sessionManager.isSessionActive()
        val block = viewBinding.blockCaptureSwitch.isChecked
        val ok = lastQualityStatus == QualityStatus.OK
        viewBinding.captureButton.isEnabled = active && (!block || ok)
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
                Toast.makeText(this, "Permissions not granted by the user.", Toast.LENGTH_SHORT).show()
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
        private const val TAG = "CameraXApp"
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
