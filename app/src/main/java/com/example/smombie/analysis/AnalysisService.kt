package com.example.smombie.analysis

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Intent
import android.graphics.Bitmap
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.LifecycleCameraController
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.example.smombie.Alerter
import com.example.smombie.R
import kotlinx.coroutines.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class AnalysisService : LifecycleService() {
    private val binder = LocalBinder()
    private lateinit var alerter: Alerter
    private val scope = CoroutineScope(Job() + Dispatchers.Main)

    private lateinit var cameraController: LifecycleCameraController
    private lateinit var imageAnalysis: ImageAnalysis

    private val ortEnv: OrtEnvironment by lazy { OrtEnvironment.getEnvironment() }
    private val analysisExecutor: ExecutorService by lazy { Executors.newSingleThreadExecutor() }

    private var alertCount = 0

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onCreate() {
        super.onCreate()
        alerter = Alerter(this)
        cameraController = LifecycleCameraController(this)
        cameraController.bindToLifecycle(this)
        startCamera()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::alerter.isInitialized) {
            alerter.hide()
        }
    }

    private fun updateUI(result: AnalysisResult, bitmap: Bitmap) {
        Log.d(TAG, "${result.detectedLabel}, ${result.detectedScore}")
        if (result.detectedScore < THRESH_HOLD) {
            return
        }

        if (result.detectedLabel != ORTAnalyzer.labels[0]) {
            alertCount += 1
        }

        Handler(Looper.getMainLooper()).post {
            if (result.detectedLabel == ORTAnalyzer.labels[0]) {
                alerter.hide()
                alertCount = 0
            } else if (alertCount > 5) {
                alerter.show(bitmap)
            }
        }
    }

    private suspend fun createOrtSession(): OrtSession? {
        return withContext(Dispatchers.IO) {
            val model = resources.openRawResource(R.raw.test).readBytes()
            ortEnv.createSession(model)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener(Runnable {
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    this, cameraSelector, imageAnalysis
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }

            setAnalyzer()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun setAnalyzer() {
        scope.launch {
            imageAnalysis.clearAnalyzer()
            imageAnalysis.setAnalyzer(
                analysisExecutor, ORTAnalyzer(createOrtSession(), ::updateUI)
            )
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): AnalysisService = this@AnalysisService
    }

    companion object {
        private const val TAG = "AnalysisService"
        private const val THRESH_HOLD = 0.8
    }
}