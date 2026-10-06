package com.example.camera_app

import android.content.pm.PackageManager
import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

/** Connects Flutter's generated Pigeon API to the Android camera host. */
class MainActivity : FlutterActivity() {
    private var cameraHost: AndroidCameraHost? = null

    /** Creates the camera host and registers it with this Flutter engine. */
    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        cameraHost = AndroidCameraHost(this, flutterEngine.renderer)
        CameraHostApi.setUp(flutterEngine.dartExecutor.binaryMessenger, cameraHost)
    }

    /** Completes the permission request waiting inside the camera host. */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == AndroidCameraHost.CAMERA_PERMISSION_REQUEST) {
            cameraHost?.onCameraPermissionResult(
                grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED,
            )
        }
    }

    /** Unregisters Pigeon handlers and releases cameras before the activity ends. */
    override fun onDestroy() {
        flutterEngine?.dartExecutor?.binaryMessenger?.let {
            CameraHostApi.setUp(it, null)
        }
        cameraHost?.shutdown()
        cameraHost = null
        super.onDestroy()
    }
}
