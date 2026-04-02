package com.paul.sprintsync.sensor_native

import android.content.Context
import androidx.camera.view.PreviewView

class SensorNativePreviewPlatformView(
    context: Context,
    private val sensorNativeController: SensorNativeController,
) {
    private val previewView: PreviewView = PreviewView(context).apply {
        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        scaleType = PreviewView.ScaleType.FILL_CENTER
    }

    init {
        sensorNativeController.attachPreviewSurface(previewView)
    }

    fun getView(): PreviewView {
        return previewView
    }

    fun dispose() {
        sensorNativeController.detachPreviewSurface(previewView)
    }
}
