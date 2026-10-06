import 'package:pigeon/pigeon.dart';

/// Source contract used to generate the Dart and Kotlin Pigeon bindings.
///
/// Regenerate both platform files after changing an API method or data field.
@ConfigurePigeon(
  PigeonOptions(
    dartOut: 'lib/src/platform/camera_api.g.dart',
    kotlinOut:
        'android/app/src/main/kotlin/com/example/camera_app/CameraApi.g.kt',
    kotlinOptions: KotlinOptions(package: 'com.example.camera_app'),
  ),
)
class CameraApiConfig {}

/// Camera metadata shared between Flutter and the Android host.
class CameraDeviceInfo {
  late String id;
  late String name;
  late String facing;
  late bool isExternal;
  late int previewTextureId;
}

/// Identifies the file written for one camera recording.
class CameraRecordingInfo {
  late String cameraId;
  late String filePath;
}

/// Recording durations and encoding settings passed to the Android host.
class SosCaptureSettings {
  late int segmentDurationSeconds;
  late int preEventDurationSeconds;
  late int postEventDurationSeconds;
  late int frameRate;
  late int videoBitRate;
  late bool combinedVideoEnabled;
}

/// Native camera operations exposed to Flutter through Pigeon.
@HostApi()
abstract class CameraHostApi {
  /// Returns camera devices exposed by Android Camera2.
  @async
  List<CameraDeviceInfo> listCameras();

  /// Requests runtime camera permission if it has not already been granted.
  @async
  bool requestCameraPermission();

  /// Opens previews for the requested cameras and closes unselected previews.
  @async
  void setPreviewCameras(List<String> cameraIds);

  /// Starts rolling temporary segments for the requested camera IDs.
  @async
  void startBuffering(List<String> cameraIds, SosCaptureSettings settings);

  /// Returns the number of pre-event seconds retained for every active camera.
  @async
  int getBufferingSeconds();

  /// Returns the current SOS save progress from 0 to 100.
  @async
  int getSosProgress();

  /// Saves pre-event footage and records the configured post-event duration.
  @async
  List<CameraRecordingInfo> triggerSos();

  /// Stops rolling capture and deletes its temporary segments.
  @async
  void stopBuffering();

  /// Releases camera and preview resources when the screen is closed.
  @async
  void releaseCameras();
}
