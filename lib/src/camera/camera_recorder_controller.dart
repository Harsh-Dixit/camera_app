import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

import '../platform/camera_api.g.dart';
import 'camera_service.dart';
import 'capture_settings.dart';

/// Owns camera selection and recording state independently of the screen.
///
/// This controller validates native responses and rolls UI state back if a
/// requested preview configuration cannot be opened by the device.
class CameraRecorderController extends ChangeNotifier {
  CameraRecorderController({
    required this._service,
    required this._isAndroidPlatform,
    this.settings = const CaptureSettings(),
  });

  final CameraService _service;
  final bool _isAndroidPlatform;
  final CaptureSettings settings;
  final List<CameraDeviceInfo> _cameras = <CameraDeviceInfo>[];
  final Set<String> _selectedCameraIds = <String>{};
  final Map<String, String> _recordingPaths = <String, String>{};
  final List<List<CameraRecordingInfo>> _recordingHistory =
      <List<CameraRecordingInfo>>[];

  bool _isLoading = true;
  bool _isBusy = false;
  bool _isBuffering = false;
  bool _isCapturingSos = false;
  bool _isClosed = false;
  bool _isDisposed = false;
  int _bufferingSeconds = 0;
  int _sosProgress = 0;
  bool _isRefreshingSosProgress = false;
  DateTime? _sosCaptureStartedAt;
  String? _message;

  List<CameraDeviceInfo> get cameras => List.unmodifiable(_cameras);
  Set<String> get selectedCameraIds => Set.unmodifiable(_selectedCameraIds);
  Map<String, String> get recordingPaths => Map.unmodifiable(_recordingPaths);
  List<List<CameraRecordingInfo>> get recordingHistory =>
      List.unmodifiable(
        _recordingHistory.map(
          (batch) => List<CameraRecordingInfo>.unmodifiable(batch),
        ),
      );
  bool get isLoading => _isLoading;
  bool get isBusy => _isBusy;
  bool get isBuffering => _isBuffering;
  bool get isCapturingSos => _isCapturingSos;
  int get sosProgress => _sosProgress;
  // SOS is available as soon as native segment capture is running. The
  // available pre-roll affects clip length, not whether the emergency action works.
  bool get isSosReady => _isBuffering && !_isCapturingSos;
  bool get isAndroidPlatform => _isAndroidPlatform;
  int get bufferingSeconds => _bufferingSeconds;
  int get availablePreEventSeconds =>
      _bufferingSeconds.clamp(0, settings.preEventDurationSeconds);
  int get sosSecondsRemaining {
    if (!_isCapturingSos || _sosCaptureStartedAt == null) return 0;
    final elapsed = DateTime.now().difference(_sosCaptureStartedAt!).inSeconds;
    return (settings.postEventDurationSeconds - elapsed).clamp(
      0,
      settings.postEventDurationSeconds,
    );
  }

  String? get message => _message;

  /// Requests permission, then loads the current Camera2 device list.
  Future<void> initialize() async {
    if (!_isAndroidPlatform || _isClosed) {
      _isLoading = false;
      _notifyListeners();
      return;
    }

    _isLoading = true;
    _message = null;
    _notifyListeners();

    try {
      final granted = await _service.requestPermission();
      if (_isClosed) return;
      if (!granted) {
        _message = 'Camera permission is needed to find and use your cameras.';
        return;
      }

      final cameras = await _service.listCameras();
      if (_isClosed) return;
      _cameras
        ..clear()
        ..addAll(cameras);
      _selectedCameraIds.removeWhere(
        (id) => !_cameras.any((camera) => camera.id == id),
      );
      if (_cameras.isEmpty) {
        _message =
            'No cameras found. Connect a supported USB camera and refresh.';
      }
    } catch (error) {
      _message = _errorMessage(error, 'Could not initialize the cameras.');
    } finally {
      _isLoading = false;
      _notifyListeners();
    }
  }

  /// Changes one preview and reverts the selection if native setup fails.
  Future<void> toggleCamera(String cameraId, bool selected) async {
    if (_isBusy || _isBuffering || _isClosed) return;
    if (!_cameras.any((camera) => camera.id == cameraId)) {
      _message = 'Camera $cameraId is no longer available. Refresh the list.';
      _notifyListeners();
      return;
    }

    final previous = Set<String>.of(_selectedCameraIds);
    if (selected) {
      _selectedCameraIds.add(cameraId);
    } else {
      _selectedCameraIds.remove(cameraId);
    }
    await _applyPreviewSelection(previous);
  }

  /// Opens every available preview, or clears the selection when all are on.
  Future<void> toggleAllCameras() async {
    if (_isBusy || _isBuffering || _isClosed || _cameras.isEmpty) return;

    final previous = Set<String>.of(_selectedCameraIds);
    _selectedCameraIds
      ..clear()
      ..addAll(
        previous.length == _cameras.length
            ? const <String>{}
            : _cameras.map((camera) => camera.id),
      );
    await _applyPreviewSelection(previous);
  }

  /// Applies the new selection to Android and restores the old selection on failure.
  Future<void> _applyPreviewSelection(Set<String> previous) async {
    _isBusy = true;
    _message = null;
    _notifyListeners();
    try {
      await _service.setPreviewCameras(_selectedCameraIds.toList());
    } catch (error) {
      _selectedCameraIds
        ..clear()
        ..addAll(previous);
      _message = _errorMessage(error, 'Could not update the camera previews.');
    } finally {
      _isBusy = false;
      _notifyListeners();
    }
  }

  /// Starts temporary segments for every selected camera.
  Future<void> startBuffering() async {
    if (_isBusy || _isBuffering || _selectedCameraIds.isEmpty || _isClosed) {
      return;
    }

    _isBusy = true;
    _message = null;
    _bufferingSeconds = 0;
    _recordingPaths.clear();
    _notifyListeners();

    try {
      await _service.startBuffering(
        List<String>.of(_selectedCameraIds),
        settings,
      );
      if (_isClosed) return;
      _isBuffering = true;
    } catch (error) {
      _message = _errorMessage(error, 'Could not start the SOS buffer.');
    } finally {
      _isBusy = false;
      _notifyListeners();
      if (_isBuffering) await refreshBufferingStatus();
    }
  }

  /// Updates the amount of pre-event footage available for the next SOS clip.
  /// Reads native buffer duration and updates the available SOS pre-roll message.
  Future<void> refreshBufferingStatus() async {
    if (!_isBuffering || _isBusy || _isClosed) return;
    final previousSeconds = _bufferingSeconds;
    final previousMessage = _message;
    try {
      final seconds = await _service.getBufferingSeconds();
      if (_isClosed) return;
      _bufferingSeconds = seconds;
      final availablePreEventSeconds = seconds.clamp(
        0,
        settings.preEventDurationSeconds,
      );
      if (availablePreEventSeconds < settings.preEventDurationSeconds) {
        _message =
            'SOS available now: $availablePreEventSeconds seconds before + '
            '${settings.postEventDurationSeconds} seconds after. Keep monitoring '
            'to increase the pre-event footage.';
      } else {
        _message =
            'SOS ready: ${settings.preEventDurationSeconds} seconds before + '
            '${settings.postEventDurationSeconds} seconds after.';
      }
    } catch (error) {
      _message = _errorMessage(error, 'Could not check SOS buffer status.');
    }
    if (_bufferingSeconds != previousSeconds || _message != previousMessage) {
      _notifyListeners();
    }
  }

  /// Saves a separate pre-event/post-event clip for each selected camera.
  Future<void> triggerSos() async {
    if (_isBusy || !isSosReady || _isClosed) return;
    _isBusy = true;
    _isCapturingSos = true;
    _sosProgress = 0;
    _sosCaptureStartedAt = DateTime.now();
    _message = null;
    _notifyListeners();

    String? captureError;
    try {
      final recordings = await _service.triggerSos();
      if (_isClosed) return;
      _sosProgress = 100;
      final returnedCameraIds = recordings.map((item) => item.cameraId).toSet();
      final selectedCameras = _cameras
          .where((camera) => _selectedCameraIds.contains(camera.id))
          .toList();
      final hasFrontAndBack = selectedCameras.any(
            (camera) => camera.facing.toLowerCase().contains('front'),
          ) &&
          selectedCameras.any(
            (camera) => camera.facing.toLowerCase().contains('back'),
          );
      final shouldSaveCombinedVideo =
          settings.combinedVideoEnabled && hasFrontAndBack;
      final combinedError = recordings.where(
        (recording) => recording.cameraId == 'combined_error',
      );
      final combinedVideoFailed = combinedError.isNotEmpty;
      final expectedCameraIds = <String>{
        ..._selectedCameraIds,
        if (shouldSaveCombinedVideo && !combinedVideoFailed) 'combined',
      };
      final allowedCameraIds = <String>{
        ...expectedCameraIds,
        if (shouldSaveCombinedVideo) 'combined_error',
      };
      if (returnedCameraIds.length != recordings.length ||
          !returnedCameraIds.containsAll(expectedCameraIds) ||
          returnedCameraIds.any((id) => !allowedCameraIds.contains(id)) ||
          recordings.any((item) => item.filePath.isEmpty)) {
        throw StateError(
          'The native camera service did not save one SOS video for every '
          'selected camera and the combined front/back video when applicable.',
        );
      }
      _recordingPaths.addEntries(
        recordings.where((recording) => recording.cameraId != 'combined_error').map(
          (recording) => MapEntry(recording.cameraId, recording.filePath),
        ),
      );
      final savedRecordings = recordings
          .where((recording) => recording.cameraId != 'combined_error')
          .toList(growable: false);
      _recordingHistory.add(List.unmodifiable(savedRecordings));
      final combinedPath = _recordingPaths['combined'];
      _message = combinedVideoFailed
          ? 'SOS #${_recordingHistory.length} saved individual camera videos, '
                'but combined front/back video failed: ${combinedError.first.filePath}'
          : combinedPath != null &&
              recordings.any((recording) => recording.cameraId == 'combined')
          ? 'SOS #${_recordingHistory.length} saved: '
                '${_selectedCameraIds.length} separate videos and combined '
                'front/back video: $combinedPath'
          : 'SOS #${_recordingHistory.length} saved: '
                '${_selectedCameraIds.length} separate video'
                '${_selectedCameraIds.length == 1 ? '' : 's'}.';
    } catch (error) {
      captureError = _errorMessage(error, 'Could not save the SOS videos.');
    } finally {
      _isCapturingSos = false;
      _sosCaptureStartedAt = null;
      if (captureError != null) _sosProgress = 0;
      _isBusy = false;
      _notifyListeners();
    }
    if (captureError != null) {
      await refreshBufferingStatus();
      if (!_isClosed) {
        _message = captureError;
        _notifyListeners();
      }
    }
  }

  /// Polls progress reported by native capture/assembly work.
  /// Reads native save progress so Provider listeners can update the progress bar.
  Future<void> refreshSosProgress() async {
    if (!_isCapturingSos || _isClosed || _isRefreshingSosProgress) return;
    _isRefreshingSosProgress = true;
    try {
      final progress = await _service.getSosProgress();
      if (_isClosed || !_isCapturingSos) return;
      final boundedProgress = progress.clamp(0, 99);
      if (boundedProgress > _sosProgress) {
        _sosProgress = boundedProgress;
        _notifyListeners();
      }
    } catch (error) {
      if (!_isClosed && _isCapturingSos) {
        _message = _errorMessage(error, 'Could not read SOS save progress.');
        _notifyListeners();
      }
    } finally {
      _isRefreshingSosProgress = false;
    }
  }

  /// Stops rolling capture and discards all untriggered temporary segments.
  /// Stops rolling capture and removes temporary segments that were not saved.
  Future<void> stopBuffering() async {
    if (_isBusy || !_isBuffering || _isClosed) return;
    _isBusy = true;
    _message = null;
    _notifyListeners();
    try {
      await _service.stopBuffering();
      _isBuffering = false;
      _bufferingSeconds = 0;
      _message = 'SOS monitoring stopped. Untriggered video was discarded.';
    } catch (error) {
      _message = _errorMessage(error, 'Could not stop SOS monitoring.');
    } finally {
      _isBusy = false;
      _notifyListeners();
    }
  }

  /// Stops native camera activity when the screen is disposed.
  /// Releases native cameras when Provider removes this controller from the tree.
  Future<void> close() async {
    if (_isClosed) return;
    _isClosed = true;
    await _releaseNativeCameras();
    dispose();
  }

  /// Releases native cameras when the Provider disposes this state object.
  @override
  void dispose() {
    if (_isDisposed) return;
    _isDisposed = true;
    if (!_isClosed) {
      _isClosed = true;
      unawaited(_releaseNativeCameras());
    }
    super.dispose();
  }

  /// Performs native camera cleanup and reports failures to Flutter's error handler.
  Future<void> _releaseNativeCameras() async {
    if (_isAndroidPlatform) {
      try {
        await _service.releaseCameras();
      } catch (error, stackTrace) {
        FlutterError.reportError(
          FlutterErrorDetails(
            exception: error,
            stack: stackTrace,
            library: 'camera recorder',
            context: ErrorDescription(
              'while releasing native camera resources',
            ),
          ),
        );
      }
    }
  }

  /// Chooses a user-readable error from a platform failure or fallback message.
  String _errorMessage(Object error, String fallback) {
    if (error is PlatformException && error.message != null) {
      return error.message!;
    }
    final text = error.toString();
    return text.isEmpty ? fallback : '$fallback $text';
  }

  /// Notifies the UI only while this controller is still active.
  void _notifyListeners() {
    if (!_isClosed) notifyListeners();
  }
}
