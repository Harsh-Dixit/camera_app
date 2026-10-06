import '../platform/camera_api.g.dart';

/// Central defaults and tunable parameters for rolling SOS capture.
///
/// Pass a different instance to [CameraRecorderController] to change these
/// settings without changing camera-service or screen logic.
class CaptureSettings {
  const CaptureSettings({
    this.segmentDurationSeconds = 10,
    this.preEventDurationSeconds = 30,
    this.postEventDurationSeconds = 30,
    this.frameRate = 15,
    this.videoBitRate = 1500000,
  });

  final int segmentDurationSeconds;
  final int preEventDurationSeconds;
  final int postEventDurationSeconds;
  final int frameRate;
  final int videoBitRate;

  SosCaptureSettings toPlatformSettings() => SosCaptureSettings(
    segmentDurationSeconds: segmentDurationSeconds,
    preEventDurationSeconds: preEventDurationSeconds,
    postEventDurationSeconds: postEventDurationSeconds,
    frameRate: frameRate,
    videoBitRate: videoBitRate,
  );
}
