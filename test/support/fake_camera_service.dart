import 'package:camera_app/src/camera/camera_service.dart';
import 'package:camera_app/src/camera/capture_settings.dart';
import 'package:camera_app/src/platform/camera_api.g.dart';

/// In-memory native-camera substitute for controller and widget tests.
///
/// Captures API calls and can inject failures without requiring camera hardware.
class FakeCameraService implements CameraService {
  bool permissionGranted = true;
  Object? listError;
  Object? previewError;
  Object? startError;
  Object? stopError;
  Object? bufferStatusError;
  List<CameraDeviceInfo> cameras = testCameras;
  Object? sosError;
  List<CameraRecordingInfo>? sosResult;
  int bufferingSeconds = 30;
  CaptureSettings? receivedSettings;

  int permissionRequests = 0;
  int cameraListRequests = 0;
  int releaseRequests = 0;
  final List<List<String>> previewSelections = <List<String>>[];
  final List<List<String>> bufferingSelections = <List<String>>[];
  int sosRequests = 0;
  int stopBufferingRequests = 0;

  @override
  Future<bool> requestPermission() async {
    permissionRequests++;
    return permissionGranted;
  }

  @override
  Future<List<CameraDeviceInfo>> listCameras() async {
    cameraListRequests++;
    if (listError case final error?) throw error;
    return cameras;
  }

  @override
  Future<void> setPreviewCameras(List<String> cameraIds) async {
    previewSelections.add(List<String>.of(cameraIds));
    if (previewError case final error?) throw error;
  }

  @override
  Future<void> startBuffering(
    List<String> cameraIds,
    CaptureSettings settings,
  ) async {
    bufferingSelections.add(List<String>.of(cameraIds));
    receivedSettings = settings;
    if (startError case final error?) throw error;
  }

  @override
  Future<int> getBufferingSeconds() async {
    if (bufferStatusError case final error?) throw error;
    return bufferingSeconds;
  }

  @override
  Future<List<CameraRecordingInfo>> triggerSos() async {
    sosRequests++;
    if (sosError case final error?) throw error;
    return sosResult ??
        bufferingSelections.last.map(recordingFor).toList();
  }

  @override
  Future<void> stopBuffering() async {
    stopBufferingRequests++;
    if (stopError case final error?) throw error;
  }

  @override
  Future<void> releaseCameras() async {
    releaseRequests++;
  }

  static final testCameras = <CameraDeviceInfo>[
    CameraDeviceInfo(
      id: 'front',
      name: 'Front camera',
      facing: 'Front',
      isExternal: false,
      previewTextureId: 1,
    ),
    CameraDeviceInfo(
      id: 'back',
      name: 'Back camera',
      facing: 'Back',
      isExternal: false,
      previewTextureId: 2,
    ),
    CameraDeviceInfo(
      id: 'usb',
      name: 'USB camera',
      facing: 'USB / external',
      isExternal: true,
      previewTextureId: 3,
    ),
  ];

  static CameraRecordingInfo recordingFor(String cameraId) =>
      CameraRecordingInfo(
        cameraId: cameraId,
        filePath: '/movies/camera_$cameraId.mp4',
      );
}
