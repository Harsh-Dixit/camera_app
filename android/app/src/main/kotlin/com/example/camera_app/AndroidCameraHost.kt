package com.example.camera_app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaExtractor
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaMetadataRetriever
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.view.TextureRegistry
import java.io.File
import java.nio.ByteBuffer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * Implements rolling SOS recording with Camera2 and MediaRecorder.
 *
 * Each selected camera continuously writes temporary, short MP4 segments. Old
 * segments are deleted as the buffer advances. An SOS event pins the requested
 * time range, records the post-event window, and joins the relevant segments
 * into one saved video for each camera.
 */
internal class AndroidCameraHost(
    private val activity: Activity,
    private val textureRegistry: TextureRegistry,
) : CameraHostApi {
    private val cameraManager =
        activity.getSystemService(Context.CAMERA_SERVICE) as CameraManager
    private val cameraThread = HandlerThread("camera-recorder").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameras = ConcurrentHashMap<String, CameraResource>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var permissionContinuation: CancellableContinuation<Boolean>? = null
    private var settings: SosCaptureSettings? = null
    private var bufferCameraIds: Set<String> = emptySet()
    private var sosInProgress = false
    @Volatile
    private var sosProgress = 0

    /** Returns immediately for an existing grant or waits for Android's dialog. */
    override suspend fun requestCameraPermission(): Boolean {
        if (
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return true
        }

        return suspendCancellableCoroutine { continuation ->
            if (permissionContinuation != null) {
                continuation.resumeWithException(
                    IllegalStateException("A camera permission request is already in progress."),
                )
                return@suspendCancellableCoroutine
            }
            permissionContinuation = continuation
            continuation.invokeOnCancellation {
                if (permissionContinuation === continuation) {
                    permissionContinuation = null
                }
            }
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST,
            )
        }
    }

    /** Resumes the Pigeon permission call after Android reports the user's choice. */
    fun onCameraPermissionResult(granted: Boolean) {
        permissionContinuation?.let {
            permissionContinuation = null
            it.resume(granted)
        }
    }

    /**
     * Lists every camera Android exposes through Camera2, including external
     * USB cameras, and creates a preview texture for each camera.
     */
    override suspend fun listCameras(): List<CameraDeviceInfo> {
        ensureCameraPermission()
        removeStaleTemporarySegments()
        val cameraIds = cameraManager.cameraIdList.toSet()
        cameras.keys.filterNot(cameraIds::contains).forEach { id ->
            cameras[id]?.let(::releaseCamera)
        }
        return cameraIds.map { id ->
            val characteristics = cameraManager.getCameraCharacteristics(id)
            val facing =
                when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
                    CameraCharacteristics.LENS_FACING_FRONT -> "Front"
                    CameraCharacteristics.LENS_FACING_BACK -> "Back"
                    CameraCharacteristics.LENS_FACING_EXTERNAL -> "USB / external"
                    else -> "Camera"
                }
            val isExternal =
                characteristics.get(CameraCharacteristics.LENS_FACING) ==
                    CameraCharacteristics.LENS_FACING_EXTERNAL
            val videoSize = selectVideoSize(id, characteristics)
            val resource =
                cameras.computeIfAbsent(id) {
                    val entry = textureRegistry.createSurfaceTexture()
                    entry.surfaceTexture().setDefaultBufferSize(
                        videoSize.width,
                        videoSize.height,
                    )
                    CameraResource(
                        id = id,
                        previewEntry = entry,
                        previewSurface = Surface(entry.surfaceTexture()),
                        videoSize = videoSize,
                    )
                }
            CameraDeviceInfo(
                id = id,
                name = if (isExternal) "$facing camera ($id)" else "$facing camera",
                facing = facing,
                isExternal = isExternal,
                previewTextureId = resource.previewEntry.id(),
            )
        }
    }

    /** Opens requested previews and closes previews that are no longer selected. */
    override suspend fun setPreviewCameras(cameraIds: List<String>) {
        ensureCameraPermission()
        val ids = validateCameraIds(cameraIds, allowEmpty = true)
        withContext(Dispatchers.IO) {
            if (cameras.values.any(CameraResource::isBuffering)) {
                throw IllegalStateException("Stop the rolling buffer before changing cameras.")
            }
            val selected = ids.toSet()
            cameras.values.filterNot { it.id in selected }.forEach(::closeCameraDevice)
            try {
                ids.forEach { id ->
                    val resource = requireCameraResource(id)
                    if (resource.device == null) {
                        resource.device = openCamera(id)
                    }
                    if (resource.session == null) {
                        configurePreviewSession(resource)
                    }
                }
            } catch (error: Throwable) {
                ids.forEach { cameras[it]?.let(::closeCameraDevice) }
                throw error
            }
        }
    }

    /** Starts one rolling segment loop for every selected camera. */
    override suspend fun startBuffering(
        cameraIds: List<String>,
        settings: SosCaptureSettings,
    ) {
        ensureCameraPermission()
        validateSettings(settings)
        val ids = validateCameraIds(cameraIds, allowEmpty = false)
        checkConcurrentCameraSupport(ids)
        if (cameras.values.any(CameraResource::isBuffering)) {
            throw IllegalStateException("A rolling buffer is already running.")
        }
        this.settings = settings
        bufferCameraIds = ids.toSet()

        try {
            coroutineScope {
                ids.map { id ->
                    async(Dispatchers.IO) {
                        val resource = requireCameraResource(id)
                        resource.segmentFiles.clear()
                        resource.bufferStartedAtMs = 0L
                        resource.protectedWindowStartMs = null
                        resource.bufferError = null
                        resource.isBuffering = true
                        if (resource.device == null) {
                            resource.device = openCamera(id)
                        }
                        startSegment(resource)
                        resource.bufferStartedAtMs = resource.activeSegment!!.startTimeMs
                        resource.segmentJob = ioScope.launch { segmentLoop(resource) }
                    }
                }.awaitAll()
            }
        } catch (error: Throwable) {
            try {
                stopBuffering()
            } catch (cleanupError: Throwable) {
                error.addSuppressed(cleanupError)
            }
            throw error
        }
    }

    /** Reports the shortest currently buffered duration among active cameras. */
    override suspend fun getBufferingSeconds(): Long {
        val active = bufferCameraIds.mapNotNull(cameras::get)
        if (active.isEmpty()) return 0
        active.firstNotNullOfOrNull(CameraResource::bufferError)?.let { error ->
            throw IllegalStateException("Camera buffering failed: ${error.message}", error)
        }
        if (active.any { !it.isBuffering }) {
            throw IllegalStateException(
                "One or more selected cameras stopped buffering. Stop and restart SOS monitoring.",
            )
        }
        if (active.any { it.bufferStartedAtMs == 0L }) return 0
        val now = SystemClock.elapsedRealtime()
        return active.minOf { resource ->
            ((now - resource.bufferStartedAtMs).coerceAtLeast(0L) / 1000L)
                .coerceAtMost(requireNotNull(settings).preEventDurationSeconds.toLong())
        }
    }

    /** Reports native progress while an SOS request is recording or being assembled. */
    override suspend fun getSosProgress(): Long = sosProgress.toLong()

    /** Saves available pre-event footage and then records the post-event window. */
    override suspend fun triggerSos(): List<CameraRecordingInfo> {
        val captureSettings = settings
            ?: throw IllegalStateException("Start the rolling buffer before pressing SOS.")
        val active = bufferCameraIds.map(::requireCameraResource)
        if (active.isEmpty()) {
            throw IllegalStateException("No camera is currently armed for SOS recording.")
        }
        active.firstNotNullOfOrNull(CameraResource::bufferError)?.let { error ->
            throw IllegalStateException("Camera buffering failed: ${error.message}", error)
        }
        if (active.any { !it.isBuffering }) {
            throw IllegalStateException("A selected camera is no longer buffering.")
        }
        if (sosInProgress) {
            throw IllegalStateException("An SOS clip is already being saved.")
        }

        val eventStartMs = SystemClock.elapsedRealtime()
        val preEventMs = captureSettings.preEventDurationSeconds * 1000L
        // Each camera may finish opening a few milliseconds apart. Use the
        // footage that camera actually has instead of waiting for a full pre-roll.
        val eventWindowStarts = active.associateWith { resource ->
            maxOf(eventStartMs - preEventMs, resource.bufferStartedAtMs)
        }

        sosInProgress = true
        sosProgress = 0
        var sosSaved = false
        active.forEach { resource ->
            resource.protectedWindowStartMs = eventWindowStarts.getValue(resource)
        }
        return try {
            // Use the normal segment rotation loop instead of rebuilding both
            // camera sessions at SOS time. Reconfiguring concurrent sessions
            // here can freeze a preview on some OEM camera providers.
            val eventEndMs =
                eventStartMs + captureSettings.postEventDurationSeconds * 1000L
            while (SystemClock.elapsedRealtime() < eventEndMs) {
                val elapsedMs = SystemClock.elapsedRealtime() - eventStartMs
                sosProgress = (
                    5L + elapsedMs * 45L /
                        (captureSettings.postEventDurationSeconds * 1000L)
                    ).toInt().coerceIn(5, 50)
                delay((eventEndMs - SystemClock.elapsedRealtime()).coerceAtMost(250L).coerceAtLeast(1L))
            }
            sosProgress = 50

            while (true) {
                val waitingForFinalizedSegment = active.filter { resource ->
                    resource.segmentMutex.withLock {
                        resource.segmentFiles.none { segment ->
                            segment.endTimeMs >= eventEndMs
                        }
                    }
                }
                if (waitingForFinalizedSegment.isEmpty()) break
                waitingForFinalizedSegment.firstNotNullOfOrNull(CameraResource::bufferError)
                    ?.let { error ->
                        throw IllegalStateException(
                            "A camera stopped before the SOS post-event video was finalized.",
                            error,
                        )
                    }
                if (waitingForFinalizedSegment.any { !it.isBuffering }) {
                    throw IllegalStateException(
                        "A camera stopped before the SOS post-event video was finalized.",
                    )
                }
                delay(100L)
            }

            val segmentFilesByCamera = active.associateWith { resource ->
                resource.segmentMutex.withLock {
                    resource.segmentFiles
                        .filter { segment ->
                            segment.endTimeMs > eventWindowStarts.getValue(resource) &&
                                segment.startTimeMs < eventEndMs
                        }
                        .sortedBy(SegmentFile::startTimeMs)
                }
            }
            sosProgress = 55
            val completedVideoCount = AtomicInteger(0)
            val recordings = coroutineScope {
                        active.map { resource ->
                            async(Dispatchers.IO) {
                        val segments = segmentFilesByCamera.getValue(resource)
                        if (segments.isEmpty()) {
                            throw IllegalStateException(
                                "No video segments are available for ${resource.id}.",
                            )
                        }
                        val output = createSosOutputFile(resource.id)
                        try {
                            mergeSegments(
                                segments,
                                eventWindowStarts.getValue(resource),
                                eventEndMs,
                                output,
                            )
                            sosProgress = (
                                55 + completedVideoCount.incrementAndGet() * 20 / active.size
                                ).coerceAtMost(75)
                        } catch (error: Throwable) {
                            output.delete()
                            throw error
                        }
                        CameraRecordingInfo(resource.id, output.absolutePath)
                    }
                }.awaitAll()
            }
            val frontCamera = active.firstOrNull { cameraFacing(it.id) == CameraCharacteristics.LENS_FACING_FRONT }
            val backCamera = active.firstOrNull { cameraFacing(it.id) == CameraCharacteristics.LENS_FACING_BACK }
            if (captureSettings.combinedVideoEnabled && frontCamera != null && backCamera != null) {
                val completedRecordings = recordings.associateBy(CameraRecordingInfo::cameraId)
                val frontVideo = completedRecordings[frontCamera.id]
                    ?: throw IllegalStateException("Completed front-camera SOS video is missing.")
                val backVideo = completedRecordings[backCamera.id]
                    ?: throw IllegalStateException("Completed back-camera SOS video is missing.")
                val combinedOutput = createCombinedSosOutputFile()
                sosProgress = 75
                try {
                    withContext(Dispatchers.IO) {
                        composeFrontAndBackVideo(
                            File(frontVideo.filePath),
                            File(backVideo.filePath),
                            recordingRotationDegrees(frontCamera.id),
                            recordingRotationDegrees(backCamera.id),
                            captureSettings.frameRate.toInt(),
                            captureSettings.videoBitRate.toInt(),
                            combinedOutput,
                            onProgress = { frameProgress ->
                                sosProgress = 75 + (frameProgress * 24).toInt().coerceIn(0, 24)
                            },
                        )
                    }
                } catch (error: Throwable) {
                    if (combinedOutput.exists() && !combinedOutput.delete()) {
                        error.addSuppressed(
                            IllegalStateException(
                                "Could not remove incomplete combined SOS output ${combinedOutput.absolutePath}.",
                            ),
                        )
                    }
                    Log.e(TAG, "Could not compose the combined front/back SOS video.", error)
                    sosSaved = true
                    sosProgress = 100
                    return recordings + CameraRecordingInfo(
                        COMBINED_ERROR_CAMERA_ID,
                        error.message ?: "Unknown combined-video encoding error.",
                    )
                }
                sosSaved = true
                sosProgress = 100
                recordings + CameraRecordingInfo(COMBINED_CAMERA_ID, combinedOutput.absolutePath)
            } else {
                sosSaved = true
                sosProgress = 100
                recordings
            }
        } finally {
            withContext(NonCancellable) {
                active.forEach { resource ->
                    resource.protectedWindowStartMs = null
                    try {
                        evictOldSegments(resource)
                    } catch (error: Throwable) {
                        Log.e(TAG, "Could not evict old SOS segments for ${resource.id}.", error)
                    }
                }
                sosInProgress = false
                if (!sosSaved) sosProgress = 0
            }
        }
    }

    /** Stops camera segment loops and deletes all temporary rolling footage. */
    override suspend fun stopBuffering() {
        if (sosInProgress) {
            throw IllegalStateException("Wait for the current SOS clip to finish saving.")
        }
        val active = cameras.values.filter {
            it.isBuffering ||
                it.segmentJob != null ||
                it.activeSegment != null
        }
        active.forEach { it.isBuffering = false }
        active.mapNotNull(CameraResource::segmentJob).forEach { it.cancel() }
        active.mapNotNull(CameraResource::segmentJob).forEach { it.join() }
        withContext(Dispatchers.IO) {
            active.forEach { resource ->
                resource.segmentMutex.withLock {
                    try {
                        finishCurrentSegment(resource)
                    } catch (error: Throwable) {
                        Log.e(TAG, "Could not finish ${resource.id} rolling segment.", error)
                    } finally {
                        resource.activeSegment?.file?.delete()
                        resource.activeSegment = null
                        resource.segmentFiles.forEach { it.file.delete() }
                        resource.segmentFiles.clear()
                        resource.segmentJob = null
                        resource.bufferStartedAtMs = 0L
                        resource.protectedWindowStartMs = null
                        resource.bufferError = null
                        resource.recorder?.release()
                        resource.recorder = null
                        resource.outputPath = null
                        resource.session?.close()
                        resource.session = null
                        if (resource.device != null) {
                            try {
                                configurePreviewSession(resource)
                            } catch (error: Throwable) {
                                Log.e(TAG, "Could not restore ${resource.id} preview.", error)
                            }
                        }
                    }
                }
            }
        }
        bufferCameraIds = emptySet()
        settings = null
    }

    /** Stops any buffer before releasing camera and texture resources. */
    override suspend fun releaseCameras() {
        stopBuffering()
        withContext(Dispatchers.IO) {
            cameras.values.toList().forEach(::releaseCamera)
        }
    }

    /** Synchronously releases resources when the Android activity is destroyed. */
    fun shutdown() {
        permissionContinuation?.let {
            permissionContinuation = null
            it.resume(false)
        }
        ioScope.cancel()
        cameras.values.toList().forEach(::releaseCamera)
        cameraThread.quitSafely()
    }

    private fun validateSettings(settings: SosCaptureSettings) {
        require(settings.segmentDurationSeconds in 1..60) {
            "Segment length must be between 1 and 60 seconds."
        }
        require(settings.preEventDurationSeconds in 1..300) {
            "Pre-event duration must be between 1 and 300 seconds."
        }
        require(settings.postEventDurationSeconds in 1..300) {
            "Post-event duration must be between 1 and 300 seconds."
        }
        require(settings.frameRate in 1..30) {
            "Frame rate must be between 1 and 30 fps."
        }
        require(settings.videoBitRate in 250_000..20_000_000) {
            "Video bitrate must be between 250 kbps and 20 Mbps."
        }
    }

    /** Ensures camera operations are only attempted after runtime permission. */
    private fun ensureCameraPermission() {
        if (
            ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException("Camera permission has not been granted.")
        }
    }

    /** Removes duplicates and rejects empty or stale camera IDs when not allowed. */
    private fun validateCameraIds(
        cameraIds: List<String>,
        allowEmpty: Boolean,
    ): List<String> {
        val ids = cameraIds.distinct()
        if (!allowEmpty && ids.isEmpty()) {
            throw IllegalArgumentException("Select at least one camera.")
        }
        ids.forEach(::requireCameraResource)
        return ids
    }

    /** Returns a known camera resource or reports that the device disappeared. */
    private fun requireCameraResource(id: String): CameraResource =
        cameras[id] ?: throw IllegalArgumentException(
            "Camera $id is no longer available. Refresh the camera list.",
        )

    /**
     * Checks Android's advertised combinations but lets the actual capture
     * session decide. Some OEM camera providers support combinations that are
     * missing from this capability list.
     */
    private fun checkConcurrentCameraSupport(cameraIds: List<String>) {
        if (cameraIds.size < 2 || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return
        }
        val cameraSets =
            try {
                cameraManager.concurrentCameraIds
            } catch (error: CameraAccessException) {
                throw IllegalStateException(
                    "Android could not determine which cameras can record together.",
                    error,
                )
            }
        if (cameraSets.none { it.containsAll(cameraIds) }) {
            Log.w(
                TAG,
                "Android does not advertise concurrent capture for $cameraIds " +
                    "(advertised combinations: $cameraSets); trying actual camera sessions.",
            )
        }
    }

    /** Keeps completed segments needed for the pre-event window and evicts older files. */
    private suspend fun evictOldSegments(resource: CameraResource) {
        resource.segmentMutex.withLock {
            val currentSettings = settings ?: return@withLock
            if (resource.protectedWindowStartMs != null) return@withLock
            val retentionStart =
                SystemClock.elapsedRealtime() -
                    (currentSettings.preEventDurationSeconds +
                        currentSettings.segmentDurationSeconds) * 1000L
            val oldSegments =
                resource.segmentFiles.filter { it.endTimeMs <= retentionStart }
            oldSegments.forEach { it.file.delete() }
            resource.segmentFiles.removeAll(oldSegments.toSet())
        }
    }

    /** Rotates files on their configured duration without blocking other cameras. */
    private suspend fun segmentLoop(resource: CameraResource) {
        val segmentMs = requireNotNull(settings).segmentDurationSeconds * 1000L
        try {
            while (resource.isBuffering && ioScope.isActive) {
                val nextRolloverAt =
                    resource.activeSegment?.startTimeMs?.plus(segmentMs)
                        ?: continue
                delay(
                    (nextRolloverAt - SystemClock.elapsedRealtime())
                        .coerceAtLeast(1L),
                )
                rotateSegment(resource)
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            resource.bufferError = error
            resource.isBuffering = false
            Log.e(TAG, "Rolling recording failed for ${resource.id}.", error)
        }
    }

    /** Finalizes the active segment and immediately starts the next one. */
    private suspend fun rotateSegment(
        resource: CameraResource,
        minimumDurationMs: Long = 0L,
    ) {
        resource.segmentMutex.withLock {
            if (!resource.isBuffering) return
            val minimumStopAt =
                resource.activeSegment?.startTimeMs?.plus(minimumDurationMs)
                    ?: SystemClock.elapsedRealtime()
            val waitMs = minimumStopAt - SystemClock.elapsedRealtime()
            if (waitMs > 0L) delay(waitMs)
            finishCurrentSegment(resource)?.let(resource.segmentFiles::add)
            startSegment(resource)
        }
        evictOldSegments(resource)
    }

    /** Configures this camera's recorder and directs frames to a temporary segment file. */
    private suspend fun startSegment(resource: CameraResource) {
        val captureSettings = settings
            ?: throw IllegalStateException("Rolling capture settings are unavailable.")
        val device = resource.device ?: openCamera(resource.id).also { resource.device = it }
        val file = createTemporarySegmentFile(resource.id)
        val recorder = MediaRecorder()
        try {
            recorder.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            recorder.setVideoSize(resource.videoSize.width, resource.videoSize.height)
            recorder.setVideoFrameRate(captureSettings.frameRate.toInt())
            recorder.setVideoEncodingBitRate(captureSettings.videoBitRate.toInt())
            recorder.setOutputFile(file.absolutePath)
            recorder.prepare()
            configureRecordingSession(resource, device, recorder)
            recorder.start()
            resource.recorder = recorder
            resource.outputPath = file.absolutePath
            resource.activeSegment = SegmentFile(
                file = file,
                startTimeMs = SystemClock.elapsedRealtime(),
            )
        } catch (error: Throwable) {
            recorder.release()
            file.delete()
            resource.recorder = null
            resource.outputPath = null
            resource.activeSegment = null
            resource.session?.close()
            resource.session = null
            throw IllegalStateException(
                "Could not start rolling segment for ${resource.id}: " +
                    (error.message ?: "camera setup failed"),
                error,
            )
        }
    }

    /** Stops the active recorder and returns its real monotonic time interval. */
    private fun finishCurrentSegment(resource: CameraResource): SegmentFile? {
        val segment = resource.activeSegment ?: return null
        try {
            resource.recorder?.stop()
        } catch (error: RuntimeException) {
            segment.file.delete()
            Log.e(TAG, "Could not finalize a temporary segment for ${resource.id}.", error)
            resource.recorder?.release()
            resource.recorder = null
            resource.outputPath = null
            resource.activeSegment = null
            resource.session?.close()
            resource.session = null
            throw IllegalStateException(
                "Could not finalize a rolling segment for ${resource.id}.",
                error,
            )
        }
        resource.recorder?.release()
        resource.recorder = null
        resource.outputPath = null
        resource.session?.close()
        resource.session = null
        segment.endTimeMs = SystemClock.elapsedRealtime()
        resource.activeSegment = null
        return segment
    }

    /** Creates a preview-only Camera2 session. */
    private suspend fun configurePreviewSession(resource: CameraResource) {
        val device = resource.device ?: return
        resource.session =
            createSession(device, listOf(resource.previewSurface)) { captureSession ->
                val request =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                        addTarget(resource.previewSurface)
                    }.build()
                captureSession.setRepeatingRequest(request, null, cameraHandler)
            }
    }

    /** Sends camera frames simultaneously to its preview texture and current recorder. */
    private suspend fun configureRecordingSession(
        resource: CameraResource,
        device: CameraDevice,
        recorder: MediaRecorder,
    ) {
        val recorderSurface = recorder.surface
        resource.session?.close()
        resource.session =
            createSession(device, listOf(resource.previewSurface, recorderSurface)) {
                val request =
                    device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                        addTarget(resource.previewSurface)
                        addTarget(recorderSurface)
                    }.build()
                it.setRepeatingRequest(request, null, cameraHandler)
            }
    }

    /** Copies only the samples in the SOS time range into one MP4 container. */
    private fun mergeSegments(
        segments: List<SegmentFile>,
        windowStartMs: Long,
        windowEndMs: Long,
        outputFile: File,
    ) {
        var muxer: MediaMuxer? = null
        var outputTrack = -1
        var expectedFormat: MediaFormat? = null
        var muxerStarted = false
        var copiedSamples = 0
        var isFirstSegment = true
        val windowEndUs = windowEndMs * 1000L
        var outputOriginUs = windowStartMs * 1000L
        try {
            muxer = MediaMuxer(
                outputFile.absolutePath,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
            for (segment in segments) {
                if (segment.endTimeMs <= windowStartMs || segment.startTimeMs >= windowEndMs) {
                    continue
                }
                val extractor = MediaExtractor()
                try {
                    extractor.setDataSource(segment.file.absolutePath)
                    val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                        extractor.getTrackFormat(index)
                            .getString(MediaFormat.KEY_MIME)
                            ?.startsWith("video/") == true
                    } ?: continue
                    extractor.selectTrack(videoTrack)
                    val format = extractor.getTrackFormat(videoTrack)
                    if (!muxerStarted) {
                        expectedFormat = format
                        outputTrack = muxer.addTrack(format)
                        muxer.start()
                        muxerStarted = true
                    } else if (!formatsAreCompatible(expectedFormat!!, format)) {
                        throw IllegalStateException(
                            "Camera ${segment.file.name} changed video format during SOS capture.",
                        )
                    }

                    if (isFirstSegment) {
                        val targetInSegmentUs =
                            ((windowStartMs - segment.startTimeMs).coerceAtLeast(0L)) * 1000L
                        extractor.seekTo(
                            targetInSegmentUs,
                            MediaExtractor.SEEK_TO_PREVIOUS_SYNC,
                        )
                        val syncSampleTimeUs = extractor.sampleTime
                        if (syncSampleTimeUs >= 0L) {
                            outputOriginUs =
                                segment.startTimeMs * 1000L + syncSampleTimeUs
                        }
                        isFirstSegment = false
                    }

                    val buffer = ByteBuffer.allocateDirect(MAX_SAMPLE_BUFFER_BYTES)
                    val bufferInfo = android.media.MediaCodec.BufferInfo()
                    while (true) {
                        buffer.clear()
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) break
                        val absoluteSampleTimeUs =
                            segment.startTimeMs * 1000L + extractor.sampleTime
                        val isInCaptureWindow =
                            absoluteSampleTimeUs >= outputOriginUs &&
                                absoluteSampleTimeUs < windowEndUs
                        if (isInCaptureWindow) {
                            bufferInfo.set(
                                0,
                                sampleSize,
                                absoluteSampleTimeUs - outputOriginUs,
                                extractor.sampleFlags,
                            )
                            muxer.writeSampleData(outputTrack, buffer, bufferInfo)
                            copiedSamples++
                        }
                        if (!extractor.advance()) break
                    }
                } finally {
                    extractor.release()
                }
            }
            if (!muxerStarted || copiedSamples == 0) {
                throw IllegalStateException("No encoded video frames were available for the SOS clip.")
            }
        } finally {
            if (muxerStarted) muxer?.stop()
            muxer?.release()
        }
    }

    /**
     * Decodes the front/rear rolling segments and encodes them side-by-side
     * into one SOS video. This is post-processing; it does not bypass Camera2
     * concurrent-camera restrictions.
     */
    private fun composeFrontAndBackVideo(
        frontVideo: File,
        backVideo: File,
        frontRotationDegrees: Int,
        backRotationDegrees: Int,
        frameRate: Int,
        bitRate: Int,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ) {
        val frontSource = MediaMetadataRetriever().apply {
            setDataSource(frontVideo.absolutePath)
        }
        val backSource = MediaMetadataRetriever().apply {
            setDataSource(backVideo.absolutePath)
        }
        val frontDurationMs = frontSource.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION,
        )?.toLongOrNull() ?: 0L
        val backDurationMs = backSource.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_DURATION,
        )?.toLongOrNull() ?: 0L
        val durationMs = minOf(frontDurationMs, backDurationMs)
        val frontMetadataRotation = frontSource.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
        )?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
        val backMetadataRotation = backSource.extractMetadata(
            MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION,
        )?.toIntOrNull()?.let { ((it % 360) + 360) % 360 } ?: 0
        val frontRotation = frontMetadataRotation.takeIf { it != 0 } ?: frontRotationDegrees
        val backRotation = backMetadataRotation.takeIf { it != 0 } ?: backRotationDegrees
        if (durationMs <= 0L) {
            frontSource.release()
            backSource.release()
            throw IllegalStateException(
                "The completed front/back SOS videos do not contain readable video duration metadata.",
            )
        }
        val encoderInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .firstOrNull { info ->
                info.isEncoder &&
                    info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) } &&
                    info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .colorFormats
                        .contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            } ?: run {
                frontSource.release()
                backSource.release()
                throw IllegalStateException("This device has no compatible AVC encoder for combined video.")
            }
        val encoder = MediaCodec.createByCodecName(encoderInfo.name)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var muxerStopped = false
        var outputTrack = -1
        var muxerSamples = 0
        var outputEos = false
        var encoderStarted = false
        val width = COMBINED_VIDEO_WIDTH
        val height = COMBINED_VIDEO_HEIGHT
        val frameIntervalUs = 1_000_000L / frameRate
        val frameCount = ((durationMs * frameRate) / 1000L)
            .coerceAtLeast(1L)
        val frameBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frameBitmap)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var frontFrame: Bitmap? = null
        var backFrame: Bitmap? = null

        fun drainEncoder(endOfStream: Boolean) {
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                val outputIndex = encoder.dequeueOutputBuffer(
                    bufferInfo,
                    if (endOfStream) CODEC_TIMEOUT_US else 0L,
                )
                when {
                    outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) return
                    }
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        if (muxerStarted) {
                            throw IllegalStateException("Combined video encoder changed format unexpectedly.")
                        }
                        muxer = MediaMuxer(
                            outputFile.absolutePath,
                            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
                        )
                        outputTrack = muxer!!.addTrack(encoder.outputFormat)
                        muxer!!.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val encodedData = encoder.getOutputBuffer(outputIndex)
                            ?: throw IllegalStateException("The combined video encoder returned an empty buffer.")
                        val isCodecConfig =
                            bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (bufferInfo.size > 0 && !isCodecConfig) {
                            if (!muxerStarted) {
                                throw IllegalStateException("The combined video encoder produced data before its format.")
                            }
                            encodedData.position(bufferInfo.offset)
                            encodedData.limit(bufferInfo.offset + bufferInfo.size)
                            muxer!!.writeSampleData(outputTrack, encodedData, bufferInfo)
                            muxerSamples++
                        }
                        outputEos = bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if (outputEos) return
                    }
                }
            }
        }

        fun frameFor(
            source: MediaMetadataRetriever,
            timestampUs: Long,
            rotationDegrees: Int,
        ): Bitmap {
            val safeTimestampUs =
                timestampUs.coerceAtMost((durationMs - 1L).coerceAtLeast(0L) * 1000L)
            val decoded = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                source.getScaledFrameAtTime(
                    safeTimestampUs,
                    MediaMetadataRetriever.OPTION_CLOSEST,
                    width / 2,
                    height,
                )
            } else {
                source.getFrameAtTime(safeTimestampUs, MediaMetadataRetriever.OPTION_CLOSEST)
            } ?: throw IllegalStateException("Could not decode a completed SOS video frame.")
            if (rotationDegrees == 0) return decoded
            val transform = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
            val rotated = Bitmap.createBitmap(
                decoded,
                0,
                0,
                decoded.width,
                decoded.height,
                transform,
                true,
            )
            if (rotated !== decoded) decoded.recycle()
            return rotated
        }

        try {
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            }
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            encoderStarted = true

            for (frameNumber in 0 until frameCount) {
                val timestampUs = frameNumber * frameIntervalUs
                frontFrame?.recycle()
                backFrame?.recycle()
                frontFrame = frameFor(frontSource, timestampUs, frontRotation)
                backFrame = frameFor(backSource, timestampUs, backRotation)
                canvas.drawColor(android.graphics.Color.BLACK)
                drawFittedFrame(
                    canvas,
                    frontFrame!!,
                    Rect(0, 0, width / 2, height),
                    paint,
                )
                drawFittedFrame(
                    canvas,
                    backFrame!!,
                    Rect(width / 2, 0, width, height),
                    paint,
                )

                var inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                while (inputIndex < 0) {
                    drainEncoder(endOfStream = false)
                    inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
                }
                val inputImage = encoder.getInputImage(inputIndex)
                    ?: throw IllegalStateException("The combined video encoder has no writable frame buffer.")
                try {
                    writeBitmapToYuv420(frameBitmap, inputImage)
                } finally {
                    inputImage.close()
                }
                encoder.queueInputBuffer(
                    inputIndex,
                    0,
                    width * height * 3 / 2,
                    frameNumber * frameIntervalUs,
                    0,
                )
                drainEncoder(endOfStream = false)
                if (frameNumber % maxOf(frameCount / 100L, 1L) == 0L) {
                    onProgress((frameNumber + 1).toFloat() / frameCount)
                }
            }

            var inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            while (inputIndex < 0) {
                drainEncoder(endOfStream = false)
                inputIndex = encoder.dequeueInputBuffer(CODEC_TIMEOUT_US)
            }
            encoder.queueInputBuffer(
                inputIndex,
                0,
                0,
                frameCount * frameIntervalUs,
                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
            )
            while (!outputEos) drainEncoder(endOfStream = true)
            if (!muxerStarted || muxerSamples == 0) {
                throw IllegalStateException("The combined video encoder produced no output.")
            }
            muxerStopped = true
            muxer!!.stop()
            validateCombinedVideo(outputFile)
        } finally {
            frontFrame?.recycle()
            backFrame?.recycle()
            frameBitmap.recycle()
            frontSource.release()
            backSource.release()
            try {
                if (encoderStarted) encoder.stop()
            } finally {
                encoder.release()
                if (muxerStarted && muxerSamples > 0 && outputEos && !muxerStopped) {
                    muxer?.stop()
                }
                muxer?.release()
            }
        }
    }

    /** Rejects a finalized combined file unless Android can read video samples from it. */
    private fun validateCombinedVideo(file: File) {
        val extractor = MediaExtractor()
        var durationUs = 0L
        try {
            extractor.setDataSource(file.absolutePath)
            val videoTrack = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalStateException("The combined SOS file has no video track.")
            durationUs = extractor.getTrackFormat(videoTrack)
                .getLong(MediaFormat.KEY_DURATION)
            extractor.selectTrack(videoTrack)
            if (!extractor.advance()) {
                throw IllegalStateException("The combined SOS file contains no video samples.")
            }
        } finally {
            extractor.release()
        }
        if (durationUs <= 0L) {
            throw IllegalStateException("The combined SOS file has no playable duration.")
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            val firstFrame = retriever.getFrameAtTime(
                0L,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
            ) ?: throw IllegalStateException("The combined SOS video cannot decode its first frame.")
            firstFrame.recycle()
        } finally {
            retriever.release()
        }
    }

    /** Fits each source frame inside its panel without cropping or distortion. */
    private fun drawFittedFrame(
        canvas: Canvas,
        frame: Bitmap,
        destination: Rect,
        paint: Paint,
    ) {
        val scale = minOf(
            destination.width().toFloat() / frame.width,
            destination.height().toFloat() / frame.height,
        )
        val width = (frame.width * scale).toInt()
        val height = (frame.height * scale).toInt()
        val left = destination.left + (destination.width() - width) / 2
        val top = destination.top + (destination.height() - height) / 2
        canvas.drawBitmap(frame, null, Rect(left, top, left + width, top + height), paint)
    }

    /** Copies an ARGB bitmap into the flexible YUV420 planes expected by MediaCodec. */
    private fun writeBitmapToYuv420(bitmap: Bitmap, image: android.media.Image) {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]
        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer
        val yBufferStart = yBuffer.position()
        val uBufferStart = uBuffer.position()
        val vBufferStart = vBuffer.position()
        for (row in 0 until bitmap.height) {
            for (column in 0 until bitmap.width) {
                val color = pixels[row * bitmap.width + column]
                val red = color shr 16 and 0xff
                val green = color shr 8 and 0xff
                val blue = color and 0xff
                val y = ((66 * red + 129 * green + 25 * blue + 128) shr 8) + 16
                yBuffer.put(
                    yBufferStart + row * yPlane.rowStride + column * yPlane.pixelStride,
                    y.coerceIn(0, 255).toByte(),
                )
                if (row % 2 == 0 && column % 2 == 0) {
                    val chromaRow = row / 2
                    val chromaColumn = column / 2
                    val u = ((-38 * red - 74 * green + 112 * blue + 128) shr 8) + 128
                    val v = ((112 * red - 94 * green - 18 * blue + 128) shr 8) + 128
                    uBuffer.put(
                        uBufferStart + chromaRow * uPlane.rowStride +
                            chromaColumn * uPlane.pixelStride,
                        u.coerceIn(0, 255).toByte(),
                    )
                    vBuffer.put(
                        vBufferStart + chromaRow * vPlane.rowStride +
                            chromaColumn * vPlane.pixelStride,
                        v.coerceIn(0, 255).toByte(),
                    )
                }
            }
        }
    }

    private fun cameraFacing(cameraId: String): Int? =
        cameraManager.getCameraCharacteristics(cameraId).get(CameraCharacteristics.LENS_FACING)

    /**
     * Calculates the rotation needed to make a recorder frame upright on the
     * current display, accounting for front/rear sensor orientation.
     */
    private fun recordingRotationDegrees(cameraId: String): Int {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        val sensorOrientation =
            characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = (activity.windowManager.defaultDisplay.rotation * 90) % 360
        return when (characteristics.get(CameraCharacteristics.LENS_FACING)) {
            CameraCharacteristics.LENS_FACING_FRONT ->
                (sensorOrientation + displayDegrees) % 360
            else -> (sensorOrientation - displayDegrees + 360) % 360
        }
    }

    /** Verifies every encoded segment can share one MP4 track. */
    private fun formatsAreCompatible(first: MediaFormat, next: MediaFormat): Boolean =
        first.getString(MediaFormat.KEY_MIME) == next.getString(MediaFormat.KEY_MIME) &&
            first.getInteger(MediaFormat.KEY_WIDTH) == next.getInteger(MediaFormat.KEY_WIDTH) &&
            first.getInteger(MediaFormat.KEY_HEIGHT) == next.getInteger(MediaFormat.KEY_HEIGHT)

    /** Allocates an app-private temporary segment folder for one camera. */
    private fun createTemporarySegmentFile(cameraId: String): File {
        val safeId = cameraId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val directory = File(activity.filesDir, "rolling_camera_buffers/$safeId")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Could not create temporary video storage.")
        }
        return File(directory, "segment_${SystemClock.elapsedRealtime()}_${System.nanoTime()}.mp4")
    }

    /** Creates the durable output path for a completed SOS clip. */
    private fun createSosOutputFile(cameraId: String): File {
        val directory =
            activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: activity.filesDir
        val recordingsDirectory = File(directory, "CameraRecordings")
        if (!recordingsDirectory.exists() && !recordingsDirectory.mkdirs()) {
            throw IllegalStateException("Could not create the SOS video folder.")
        }
        val safeId = cameraId.replace(Regex("[^A-Za-z0-9_-]"), "_")
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(recordingsDirectory, "sos_${safeId}_$timestamp.mp4")
    }

    private fun createCombinedSosOutputFile(): File {
        val directory =
            activity.getExternalFilesDir(Environment.DIRECTORY_MOVIES)
                ?: activity.filesDir
        val recordingsDirectory = File(directory, "CameraRecordings")
        if (!recordingsDirectory.exists() && !recordingsDirectory.mkdirs()) {
            throw IllegalStateException("Could not create the SOS video folder.")
        }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        return File(recordingsDirectory, "sos_front_back_$timestamp.mp4")
    }

    /** Removes temporary segments left behind if Android previously killed the app. */
    private fun removeStaleTemporarySegments() {
        if (bufferCameraIds.isNotEmpty()) return
        val temporaryDirectory = File(activity.filesDir, "rolling_camera_buffers")
        if (temporaryDirectory.exists() && !temporaryDirectory.deleteRecursively()) {
            throw IllegalStateException("Could not clear stale rolling video segments.")
        }
    }

    /** Picks a camera-supported recording size, preferring up to 720p. */
    private fun selectVideoSize(
        cameraId: String,
        characteristics: CameraCharacteristics,
    ): Size {
        val streamMap = characteristics.get(
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP,
        )
        val recorderSizes = streamMap
            ?.getOutputSizes(MediaRecorder::class.java)
            ?.toList()
            .orEmpty()
        val selectedRecorderSize = recorderSizes
            .filter { it.width * it.height <= VIDEO_WIDTH * VIDEO_HEIGHT }
            .maxByOrNull { it.width * it.height }
            ?: recorderSizes.minByOrNull { it.width * it.height }
        if (selectedRecorderSize != null) return selectedRecorderSize

        val previewSize = streamMap
            ?.getOutputSizes(SurfaceTexture::class.java)
            ?.filter { it.width * it.height <= VIDEO_WIDTH * VIDEO_HEIGHT }
            ?.maxByOrNull { it.width * it.height }
        if (previewSize != null) {
            Log.w(
                TAG,
                "Camera $cameraId has no advertised MediaRecorder size; " +
                    "using $previewSize to keep its preview visible. Recording may be unsupported.",
            )
            return previewSize
        }

        Log.w(
            TAG,
            "Camera $cameraId reports no Camera2 output sizes; listing it with a VGA fallback.",
        )
        return Size(FALLBACK_VIDEO_WIDTH, FALLBACK_VIDEO_HEIGHT)
    }

    /** Bridges Camera2 callback-based session setup into a suspending call. */
    private suspend fun createSession(
        device: CameraDevice,
        surfaces: List<Surface>,
        onConfigured: (CameraCaptureSession) -> Unit,
    ): CameraCaptureSession =
        suspendCancellableCoroutine { continuation ->
            try {
                device.createCaptureSession(
                    surfaces,
                    object : CameraCaptureSession.StateCallback() {
                        override fun onConfigured(session: CameraCaptureSession) {
                            try {
                                onConfigured(session)
                                if (continuation.isActive) {
                                    continuation.resume(session)
                                } else {
                                    session.close()
                                }
                            } catch (error: Throwable) {
                                session.close()
                                if (continuation.isActive) {
                                    continuation.resumeWithException(error)
                                }
                            }
                        }

                        override fun onConfigureFailed(session: CameraCaptureSession) {
                            session.close()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("Camera session configuration failed."),
                                )
                            }
                        }
                    },
                    cameraHandler,
                )
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    /** Bridges Camera2's asynchronous open callback into a suspending call. */
    private suspend fun openCamera(cameraId: String): CameraDevice =
        suspendCancellableCoroutine { continuation ->
            try {
                cameraManager.openCamera(
                    cameraId,
                    object : CameraDevice.StateCallback() {
                        override fun onOpened(camera: CameraDevice) {
                            if (continuation.isActive) {
                                continuation.resume(camera)
                            } else {
                                camera.close()
                            }
                        }

                        override fun onDisconnected(camera: CameraDevice) {
                            camera.close()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException("Camera $cameraId disconnected."),
                                )
                            }
                        }

                        override fun onError(camera: CameraDevice, error: Int) {
                            camera.close()
                            if (continuation.isActive) {
                                continuation.resumeWithException(
                                    IllegalStateException(
                                        "Android camera error $error opening $cameraId.",
                                    ),
                                )
                            }
                        }
                    },
                    cameraHandler,
                )
            } catch (error: Throwable) {
                continuation.resumeWithException(error)
            }
        }

    /** Closes the camera session and recorder but keeps its Flutter texture alive. */
    private fun closeCameraDevice(resource: CameraResource) {
        resource.session?.close()
        resource.session = null
        resource.device?.close()
        resource.device = null
        resource.recorder?.release()
        resource.recorder = null
        resource.outputPath = null
        resource.activeSegment?.file?.delete()
        resource.activeSegment = null
        resource.isBuffering = false
    }

    /** Releases the native camera objects and the associated Flutter texture. */
    private fun releaseCamera(resource: CameraResource) {
        resource.segmentJob?.cancel()
        closeCameraDevice(resource)
        resource.segmentFiles.forEach { it.file.delete() }
        resource.segmentFiles.clear()
        resource.previewSurface.release()
        resource.previewEntry.release()
        cameras.remove(resource.id)
    }

    /** State and native objects belonging to one independently recorded camera. */
    private class CameraResource(
        val id: String,
        val previewEntry: TextureRegistry.SurfaceTextureEntry,
        val previewSurface: Surface,
        val videoSize: Size,
        val segmentMutex: Mutex = Mutex(),
        val segmentFiles: MutableList<SegmentFile> = mutableListOf(),
        var device: CameraDevice? = null,
        var session: CameraCaptureSession? = null,
        var recorder: MediaRecorder? = null,
        var outputPath: String? = null,
        @Volatile
        var activeSegment: SegmentFile? = null,
        @Volatile
        var segmentJob: Job? = null,
        @Volatile
        var bufferStartedAtMs: Long = 0L,
        var protectedWindowStartMs: Long? = null,
        @Volatile
        var bufferError: Throwable? = null,
        @Volatile
        var isBuffering: Boolean = false,
    )

    /** A temporary MP4 and its interval on Android's monotonic clock. */
    private data class SegmentFile(
        val file: File,
        val startTimeMs: Long,
        var endTimeMs: Long = Long.MAX_VALUE,
    )

    companion object {
        /** Request code used to match Android's camera permission callback. */
        const val CAMERA_PERMISSION_REQUEST = 8912
        private const val VIDEO_WIDTH = 1280
        private const val VIDEO_HEIGHT = 720
        private const val FALLBACK_VIDEO_WIDTH = 640
        private const val FALLBACK_VIDEO_HEIGHT = 480
        private const val COMBINED_VIDEO_WIDTH = 640
        private const val COMBINED_VIDEO_HEIGHT = 360
        private const val COMBINED_CAMERA_ID = "combined"
        private const val COMBINED_ERROR_CAMERA_ID = "combined_error"
        private const val CODEC_TIMEOUT_US = 10_000L
        private const val MINIMUM_SEGMENT_DURATION_MS = 1_000L
        private const val MAX_SAMPLE_BUFFER_BYTES = 8 * 1024 * 1024
        private const val TAG = "AndroidCameraHost"
    }
}
