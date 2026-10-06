import '../platform/camera_api.g.dart';
import 'capture_settings.dart';

/// Describes the camera operations used by the UI and state controller.
///
/// Keeping native calls behind this interface lets tests substitute a fake
/// service while production uses the Pigeon-generated host API.
abstract interface class CameraService {
  Future<bool> requestPermission();

  Future<List<CameraDeviceInfo>> listCameras();

  Future<void> setPreviewCameras(List<String> cameraIds);

  Future<void> startBuffering(
    List<String> cameraIds,
    CaptureSettings settings,
  );

  Future<int> getBufferingSeconds();

  Future<List<CameraRecordingInfo>> triggerSos();

  Future<void> stopBuffering();

  Future<void> releaseCameras();
}

/// Adapts the app's camera-service interface to the generated Pigeon API.
class PigeonCameraService implements CameraService {
  PigeonCameraService({CameraHostApi? api}) : _api = api ?? CameraHostApi();

  final CameraHostApi _api;

  /// Asks Android to request camera permission from the user.
  @override
  Future<bool> requestPermission() => _api.requestCameraPermission();

  /// Reads cameras currently exposed by Android Camera2.
  @override
  Future<List<CameraDeviceInfo>> listCameras() => _api.listCameras();

  /// Opens previews only for the supplied camera IDs.
  @override
  Future<void> setPreviewCameras(List<String> cameraIds) =>
      _api.setPreviewCameras(cameraIds);

  /// Starts temporary rolling segments for every supplied camera.
  @override
  Future<void> startBuffering(
    List<String> cameraIds,
    CaptureSettings settings,
  ) => _api.startBuffering(cameraIds, settings.toPlatformSettings());

  /// Reports seconds of pre-event footage currently available.
  @override
  Future<int> getBufferingSeconds() => _api.getBufferingSeconds();

  /// Creates a per-camera SOS clip with pre-event and post-event footage.
  @override
  Future<List<CameraRecordingInfo>> triggerSos() => _api.triggerSos();

  /// Discards the rolling buffer and stops its camera recorders.
  @override
  Future<void> stopBuffering() => _api.stopBuffering();

  /// Releases native camera, preview, and recorder resources.
  @override
  Future<void> releaseCameras() => _api.releaseCameras();
}
