package com.mirrorcast

import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.journeyapps.barcodescanner.CaptureActivity
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import kotlin.math.abs

open class ZoomScannerActivity : CaptureActivity() {
    override fun initializeContent(): DecoratedBarcodeView {
        val preview = DecoratedBarcodeView(this)
        val root = FrameLayout(this)
        root.addView(preview, FrameLayout.LayoutParams(-1, -1))
        val controls = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        val offset = (24 * resources.displayMetrics.density).toInt()
        root.addView(controls, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            bottomMargin = offset
        })
        var zoomTarget = 100
        fun zoom(ratio: Int) {
            zoomTarget = ratio
            preview.changeCameraParameters { parameters ->
                if (parameters.isZoomSupported) {
                    val ratios = parameters.zoomRatios.orEmpty()
                    val index = ratios.indices.minByOrNull { abs(ratios[it] - ratio) }
                    if (index != null && index <= parameters.maxZoom) parameters.zoom = index
                }
                parameters
            }
        }
        for ((ratio, label) in listOf(100 to "1×", 200 to "2×")) {
            controls.addView(Button(this).apply {
                text = label
                setOnClickListener { zoom(ratio) }
            })
        }
        preview.barcodeView.addStateListener(object : com.journeyapps.barcodescanner.CameraPreview.StateListener {
            override fun previewStarted() {
                preview.changeCameraParameters { parameters ->
                    val ratios = if (parameters.isZoomSupported) parameters.zoomRatios.orEmpty() else emptyList()
                    val index = ratios.indices.minByOrNull { abs(ratios[it] - zoomTarget) }
                    if (index != null && index <= parameters.maxZoom) parameters.zoom = index
                    val available = ratios.any { it >= 200 }
                    runOnUiThread { controls.visibility = if (available) View.VISIBLE else View.GONE }
                    parameters
                }
            }
            override fun previewSized() {}
            override fun previewStopped() { controls.visibility = View.GONE }
            override fun cameraError(error: Exception) {}
            override fun cameraClosed() {}
        })
        setContentView(root)
        return preview
    }
}
