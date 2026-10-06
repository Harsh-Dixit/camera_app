package com.example.camera_app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.MediaExtractor
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

    /** Finds Camera2 devices and creates one Flutter texture for each device. */
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
        active.forEach { resource ->
            resource.protectedWindowStartMs = eventWindowStarts.getValue(resource)
        }
        try {
            active.forEach { resource ->
                rotateSegment(resource, MINIMUM_SEGMENT_DURATION_MS)
            }
            // Start the post-event clock when fresh segments are rolling, so
            // camera startup/segment-finalization time is not taken off the
            // requested future recording window.
            val eventEndMs =
                SystemClock.elapsedRealtime() +
                    captureSettings.postEventDurationSeconds * 1000L
            while (SystemClock.elapsedRealtime() < eventEndMs) {
                delay((eventEndMs - SystemClock.elapsedRealtime()).coerceAtLeast(1L))
            }
            active.forEach { resource ->
                rotateSegment(resource, MINIMUM_SEGMENT_DURATION_MS)
            }

            return coroutineScope {
                active.map { resource ->
                    async(Dispatchers.IO) {
                        val segments =
                            resource.segmentMutex.withLock {
                                resource.segmentFiles
                                    .filter { segment ->
                                        segment.endTimeMs > eventWindowStarts.getValue(resource) &&
                                            segment.startTimeMs < eventEndMs
                                    }
                                    .sortedBy(SegmentFile::startTimeMs)
                            }
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
                        } catch (error: Throwable) {
                            output.delete()
                            throw error
                        }
                        CameraRecordingInfo(resource.id, output.absolutePath)
                    }
                }.awaitAll()
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

    /** Checks Android's advertised camera combinations before opening multiple devices. */
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
            throw IllegalStateException(
                "This device does not advertise simultaneous capture for all selected " +
                    "cameras. Try a supported pair of cameras.",
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
        val supportedSizes =
            characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(MediaRecorder::class.java)
                ?.toList()
                .orEmpty()
        return supportedSizes
            .filter { it.width * it.height <= VIDEO_WIDTH * VIDEO_HEIGHT }
            .maxByOrNull { it.width * it.height }
            ?: supportedSizes.minByOrNull { it.width * it.height }
            ?: throw IllegalStateException(
                "Camera $cameraId does not report a supported video recording size.",
            )
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
        private const val MINIMUM_SEGMENT_DURATION_MS = 1_000L
        private const val MAX_SAMPLE_BUFFER_BYTES = 8 * 1024 * 1024
        private const val TAG = "AndroidCameraHost"
    }
}
