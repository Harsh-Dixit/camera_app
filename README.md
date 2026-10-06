# Multi Camera Recorder

A Flutter Android app for previewing multiple cameras and capturing rolling SOS
video clips. Dart communicates with the Android Camera2 and MediaRecorder
implementation through generated Pigeon APIs.

For a developer-oriented explanation of the native libraries, Pigeon bridge,
recording flow, configuration, and device limitations, see [KT.md](./KT.md).

## Current support

- Android camera devices exposed by the system Camera2 provider, including
  front-facing, rear-facing, and external/USB cameras.
- The screen lists each available Camera2 device as a selectable camera card.
  Selected USB cameras use the same independent preview, rolling-buffer, and
  per-camera SOS save flow as phone cameras; combined-video processing is a
  separate optional front/back-only feature.
- After camera selection, **Start SOS monitoring** begins temporary rolling
  video segments for every selected camera.
- Pressing **SOS** saves one separate video per selected camera, containing the
  available pre-event footage and configured post-event window (up to 60
  seconds total by default).
- Video segments are muxed into one MP4 per camera. Segment boundaries can cause
  brief frame gaps on some devices; the first frame may start slightly before
  the requested pre-event boundary so the encoded video begins at a keyframe.
- SOS is available as soon as camera buffering starts. The pre-event part starts
  with whatever footage is already buffered (zero seconds immediately after
  starting) and grows to the configured 30-second maximum.
- SOS can be triggered repeatedly while monitoring remains active. Each trigger
  saves another separate set of camera clips and, when front and back are
  selected, another combined clip; earlier capture paths remain in the session
  history.
- Combined front/back output is currently disabled by default. To opt in for
  device testing, set `combinedVideoEnabled: true` in the single central
  configuration file, `lib/src/camera/capture_settings.dart`.
- Untriggered temporary segments older than the rolling window are deleted.
  **Stop monitoring** discards all remaining temporary segments.
- Default capture uses 10-second segments, 15 fps, H.264, a 1.5 Mbps target
  bitrate, and the largest camera-supported size up to 720p.
- Segment duration, pre/post-event duration, frame rate, and bitrate are
  centralized in `CaptureSettings` and passed to native code; callers can
  provide different settings without changing the recorder implementation.
- USB camera discovery and simultaneous capture depend on the phone, Android
  version, USB adapter/power, and camera provider. Refresh the camera list after
  connecting a device. An attached UVC camera that Android does not expose
  through Camera2 is not yet supported by this version.
- Android reports supported simultaneous camera combinations. When a selected
  combination is not listed, the app logs a warning and attempts to configure
  the real camera sessions anyway, because some OEM providers can expose
  combinations incompletely. The camera provider can still reject capture;
  some phones allow only two cameras or do not allow a front, rear, and USB
  camera together.
- iOS and desktop native capture are not implemented yet.

## Build and run

1. Connect an Android phone with USB debugging enabled.
2. Run `flutter pub get`.
3. Run `flutter run`.
4. Grant camera permission, select the cameras to include, and tap
   **Start SOS monitoring**.
5. Tap **SOS** at any time to save the currently available before-event footage
   plus the next 30 seconds for each selected camera. The button shows how much
   pre-event footage will be included.

Completed SOS files are saved under the app-specific Movies folder:
`Android/data/com.example.camera_app/files/Movies/CameraRecordings/`.
When both built-in front and back cameras are selected and Android allows
them to run concurrently, the app also composes their SOS footage side-by-side
into `sos_front_back_<timestamp>.mp4`. The individual per-camera SOS files are
still saved. The combined video is generated only after both individual MP4s
have been finalized, and Android checks that the resulting MP4 has a readable
video track, duration, and first frame before reporting it as saved. Combined
encoding happens after the post-event window, so it may take longer to appear
than the individual clips. This composition does not bypass Android Camera2
concurrency restrictions.
Before side-by-side composition, each source is rotated using its video
orientation metadata or the camera sensor/display orientation, so the combined
front and rear views should share the upright orientation of the active display.
While an SOS is being saved, the app polls native capture and encoding progress
and displays it on the SOS control and a progress bar. Combined composition
decodes frames at the output panel size to reduce processing time.
Untriggered rolling segments are stored temporarily in the app's private files
and are deleted as the buffer advances or when monitoring stops.
No microphone or shared-storage permission is requested.

## Pigeon API

The API contract is in `pigeons/camera_api.dart`. Regenerate its Dart and Kotlin
bindings after changing the contract:

```powershell
dart run pigeon --input pigeons\camera_api.dart
```

## Code layout

- `lib/src/camera/` contains the camera-service boundary, configurable SOS
  settings in one central config file, plus the Provider-backed recording
  controller.
- `lib/src/screens/` contains the recording screen.
- `lib/src/widgets/` contains reusable camera cards and empty-state UI.
- `lib/src/platform/camera_api.g.dart` is generated by Pigeon; do not edit it
  directly.
- `android/app/src/main/kotlin/` contains the Camera2 and MediaRecorder host.

Run `flutter test` and `flutter analyze` to check the Dart app and its
camera-controller/widget behavior tests. These tests use a fake camera service;
verify actual simultaneous-camera combinations on the target Android device.
Real Camera2 hardware, encoder, storage, and 10-second segment transitions must
still be validated on each target phone. Continuous rolling capture is active
while the app screen is open; background/locked-screen capture is not included.
