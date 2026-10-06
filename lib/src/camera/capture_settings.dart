import '../platform/camera_api.g.dart';

/// Single source of truth for camera capture and optional SOS features.
///
/// Update this file to change capture quality, clip durations, or enable
/// experimental features without changing screen or native recorder logic.
class CaptureSettings {
  const CaptureSettings({
    this.segmentDurationSeconds = 10,
    this.preEventDurationSeconds = 30,
    this.postEventDurationSeconds = 30,
    this.frameRate = 15,
    this.videoBitRate = 1500000,
    this.combinedVideoEnabled = false,
  });

  final int segmentDurationSeconds;
  final int preEventDurationSeconds;
  final int postEventDurationSeconds;
  final int frameRate;
  final int videoBitRate;

  /// Keeps side-by-side encoding off until it is enabled for a tested device.
  final bool combinedVideoEnabled;

  /// Converts app configuration into the Pigeon data model understood by Android.
  SosCaptureSettings toPlatformSettings() => SosCaptureSettings(
    segmentDurationSeconds: segmentDurationSeconds,
    preEventDurationSeconds: preEventDurationSeconds,
    postEventDurationSeconds: postEventDurationSeconds,
    frameRate: frameRate,
    videoBitRate: videoBitRate,
    combinedVideoEnabled: combinedVideoEnabled,
  );
}
