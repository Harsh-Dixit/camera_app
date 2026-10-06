 import 'dart:async';

import 'package:camera_app/src/camera/camera_recorder_controller.dart';
import 'package:camera_app/src/camera/capture_settings.dart';
import 'package:camera_app/src/platform/camera_api.g.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_camera_service.dart';

/// Verifies permission, buffer readiness, SOS capture, and cleanup behavior.
void main() {
  late FakeCameraService service;
  late CameraRecorderController controller;

  setUp(() {
    service = FakeCameraService();
    controller = CameraRecorderController(
      service: service,
      isAndroidPlatform: true,
    );
  });

  tearDown(() async {
    await controller.close();
  });

  Future<void> selectCameras(Iterable<String> cameraIds) async {
    await controller.initialize();
    for (final cameraId in cameraIds) {
      await controller.toggleCamera(cameraId, true);
    }
  }

  Future<void> enableCombinedVideoForTest() async {
    await controller.close();
    controller = CameraRecorderController(
      service: service,
      isAndroidPlatform: true,
      settings: const CaptureSettings(combinedVideoEnabled: true),
    );
  }

  test('can trigger SOS repeatedly while monitoring stays armed', () async {
    await selectCameras(['front', 'back']);
    await controller.startBuffering();

    await controller.triggerSos();
    await controller.triggerSos();
    await controller.triggerSos();

    expect(service.sosRequests, 3);
    expect(controller.isBuffering, isTrue);
    expect(controller.isSosReady, isTrue);
    expect(controller.recordingHistory, hasLength(3));
    expect(
      controller.recordingHistory.every(
        (capture) => capture.map((item) => item.cameraId).toSet().containsAll({
          'front',
          'back',
        }),
      ),
      isTrue,
    );
    expect(
      controller.recordingHistory.every(
        (capture) => capture.every((item) => item.cameraId != 'combined'),
      ),
      isTrue,
    );
    expect(controller.message, startsWith('SOS #3 saved:'));
  });

  test('refreshes native save progress while an SOS request is pending', () async {
    await selectCameras(['front']);
    await controller.startBuffering();
    final pendingSave = Completer<List<CameraRecordingInfo>>();
    service.sosCompleter = pendingSave;

    final saveFuture = controller.triggerSos();
    service.sosProgress = 42;
    await controller.refreshSosProgress();
    expect(controller.sosProgress, 42);

    pendingSave.complete([FakeCameraService.recordingFor('front')]);
    await saveFuture;
    expect(controller.sosProgress, 100);
  });

  test('keeps individual videos if combined video encoding fails', () async {
    await enableCombinedVideoForTest();
    await selectCameras(['front', 'back']);
    await controller.startBuffering();
    service.sosResult = [
      FakeCameraService.recordingFor('front'),
      FakeCameraService.recordingFor('back'),
      CameraRecordingInfo(
        cameraId: 'combined_error',
        filePath: 'AVC encoder rejected the output format.',
      ),
    ];

    await controller.triggerSos();

    expect(controller.recordingPaths.keys, {'front', 'back'});
    expect(controller.recordingHistory, hasLength(1));
    expect(controller.recordingHistory.single, hasLength(2));
    expect(controller.message, contains('individual camera videos'));
    expect(
      controller.message,
      contains('AVC encoder rejected the output format.'),
    );
    expect(controller.isBuffering, isTrue);
  });

  test('requests permission before listing the available cameras', () async {
    await controller.initialize();

    expect(service.permissionRequests, 1);
    expect(service.cameraListRequests, 1);
    expect(controller.cameras.map((camera) => camera.id), [
      'front',
      'back',
      'usb',
    ]);
    expect(controller.isLoading, isFalse);
    expect(controller.message, isNull);
  });

  test('does not list cameras when permission is denied', () async {
    service.permissionGranted = false;

    await controller.initialize();

    expect(service.cameraListRequests, 0);
    expect(controller.cameras, isEmpty);
    expect(controller.message, contains('Camera permission'));
    expect(controller.isLoading, isFalse);
  });

  test('reports platform errors while loading cameras', () async {
    service.listError = PlatformException(
      code: 'camera-access-failed',
      message: 'The camera service is unavailable.',
    );
    await controller.initialize();

    expect(controller.message, 'The camera service is unavailable.');
    expect(controller.cameras, isEmpty);
    expect(controller.isLoading, isFalse);
  });

  test('restores preview selection if native preview setup fails', () async {
    await selectCameras(['front']);
    service.previewError = PlatformException(
      code: 'concurrent-camera-unsupported',
      message: 'This camera combination is not supported.',
    );

    await controller.toggleCamera('back', true);

    expect(controller.selectedCameraIds, {'front'});
    expect(controller.message, 'This camera combination is not supported.');
    expect(controller.isBusy, isFalse);
  });

  test('select all and clear send each preview selection to native', () async {
    await controller.initialize();

    await controller.toggleAllCameras();
    await controller.toggleAllCameras();

    expect(controller.selectedCameraIds, isEmpty);
    expect(service.previewSelections.first.toSet(), {'front', 'back', 'usb'});
    expect(service.previewSelections.last, isEmpty);
  });

  test(
    'starts buffers for selected cameras with default dynamic settings',
    () async {
      await selectCameras(['front', 'usb']);

      await controller.startBuffering();

      expect(service.bufferingSelections.single.toSet(), {'front', 'usb'});
      expect(service.receivedSettings?.segmentDurationSeconds, 10);
      expect(service.receivedSettings?.preEventDurationSeconds, 30);
      expect(service.receivedSettings?.postEventDurationSeconds, 30);
      expect(service.receivedSettings?.frameRate, 15);
      expect(service.receivedSettings?.videoBitRate, 1500000);
      expect(controller.isBuffering, isTrue);
      expect(controller.isSosReady, isTrue);
      expect(controller.availablePreEventSeconds, 30);
    },
  );

  test('does not arm cameras when native buffer setup fails', () async {
    await selectCameras(['front']);
    service.startError = PlatformException(
      code: 'camera-session-failed',
      message: 'Could not start camera recording.',
    );

    await controller.startBuffering();

    expect(controller.isBuffering, isFalse);
    expect(controller.isSosReady, isFalse);
    expect(controller.message, 'Could not start camera recording.');
  });

  test(
    'allows SOS immediately and uses only the pre-event footage available',
    () async {
      service.bufferingSeconds = 12;
      await selectCameras(['front']);

      await controller.startBuffering();

      expect(controller.isBuffering, isTrue);
      expect(controller.isSosReady, isTrue);
      expect(controller.availablePreEventSeconds, 12);
      expect(
        controller.message,
        contains('12 seconds before + 30 seconds after'),
      );

      await controller.triggerSos();

      expect(service.sosRequests, 1);
      expect(controller.isCapturingSos, isFalse);
      expect(controller.recordingPaths.keys, {'front'});
    },
  );

  test(
    'allows SOS with zero pre-event footage immediately after starting',
    () async {
      service.bufferingSeconds = 0;
      await selectCameras(['front']);

      await controller.startBuffering();

      expect(controller.isSosReady, isTrue);
      expect(controller.availablePreEventSeconds, 0);
      await controller.triggerSos();
      expect(service.sosRequests, 1);
    },
  );

  test('saves one independent SOS video for each selected camera', () async {
    await selectCameras(['front', 'usb']);
    await controller.startBuffering();

    await controller.triggerSos();

    expect(service.sosRequests, 1);
    expect(controller.isBuffering, isTrue);
    expect(controller.isCapturingSos, isFalse);
    expect(controller.recordingPaths.keys.toSet(), {'front', 'usb'});
    expect(controller.recordingPaths['front'], '/movies/camera_front.mp4');
    expect(controller.recordingPaths['usb'], '/movies/camera_usb.mp4');
    expect(controller.message, 'SOS #1 saved: 2 separate videos.');
  });

  test('USB camera can be selected and saved independently', () async {
    await selectCameras(['usb']);

    expect(controller.cameras.singleWhere((camera) => camera.id == 'usb').name,
        contains('USB'));
    expect(controller.selectedCameraIds, {'usb'});

    await controller.startBuffering();
    await controller.triggerSos();

    expect(service.bufferingSelections.single, ['usb']);
    expect(controller.recordingPaths.keys, {'usb'});
    expect(controller.recordingPaths['usb'], '/movies/camera_usb.mp4');
    expect(controller.recordingPaths.containsKey('combined'), isFalse);
  });

  test(
    'saves combined video when enabled in the central config',
    () async {
      await enableCombinedVideoForTest();
      await selectCameras(['front', 'back']);
      await controller.startBuffering();

      await controller.triggerSos();

      expect(controller.recordingPaths.keys.toSet(), {
        'front',
        'back',
        'combined',
      });
      expect(
        controller.recordingPaths['combined'],
        '/movies/camera_combined.mp4',
      );
      expect(controller.message, contains('combined front/back video'));
    },
  );

  test('reports native SOS errors and keeps rolling buffer armed', () async {
    await selectCameras(['front']);
    await controller.startBuffering();
    service.sosError = PlatformException(
      code: 'video-assembly-failed',
      message: 'Could not assemble SOS video.',
    );

    await controller.triggerSos();

    expect(controller.isBuffering, isTrue);
    expect(controller.isCapturingSos, isFalse);
    expect(controller.message, 'Could not assemble SOS video.');
  });

  test(
    'allows SOS after buffering starts even if status polling fails',
    () async {
      await selectCameras(['front']);
      await controller.startBuffering();
      service.bufferingSeconds = 0;
      service.bufferStatusError = StateError(
        'Camera recorder stopped unexpectedly.',
      );

      await controller.refreshBufferingStatus();

      expect(controller.isSosReady, isTrue);
      expect(controller.message, contains('Could not check SOS buffer status'));
      await controller.triggerSos();
      expect(service.sosRequests, 1);
    },
  );

  test('rejects an incomplete native SOS result', () async {
    await controller.initialize();
    await controller.toggleAllCameras();
    await controller.startBuffering();
    service.sosResult = [
      FakeCameraService.recordingFor('front'),
      FakeCameraService.recordingFor('back'),
    ];

    await controller.triggerSos();

    expect(controller.isBuffering, isTrue);
    expect(controller.recordingPaths, isEmpty);
    expect(controller.message, contains('one SOS video for every'));
  });

  test('stops buffering and discards untriggered recordings', () async {
    await selectCameras(['front']);
    await controller.startBuffering();

    await controller.stopBuffering();

    expect(service.stopBufferingRequests, 1);
    expect(controller.isBuffering, isFalse);
    expect(controller.bufferingSeconds, 0);
    expect(
      controller.message,
      'SOS monitoring stopped. Untriggered video was discarded.',
    );
  });

  test(
    'supports settings overrides without changing camera-service logic',
    () async {
      final configuredController = CameraRecorderController(
        service: service,
        isAndroidPlatform: true,
        settings: const CaptureSettings(
          segmentDurationSeconds: 5,
          preEventDurationSeconds: 20,
          postEventDurationSeconds: 40,
          frameRate: 12,
          videoBitRate: 900000,
        ),
      );
      await configuredController.initialize();
      await configuredController.toggleCamera('front', true);

      await configuredController.startBuffering();

      expect(service.receivedSettings?.segmentDurationSeconds, 5);
      expect(service.receivedSettings?.preEventDurationSeconds, 20);
      expect(service.receivedSettings?.postEventDurationSeconds, 40);
      expect(service.receivedSettings?.frameRate, 12);
      expect(service.receivedSettings?.videoBitRate, 900000);
      expect(service.receivedSettings?.combinedVideoEnabled, isFalse);
      await configuredController.close();
    },
  );

  test('releases native cameras once when closed', () async {
    await controller.close();
    await controller.close();

    expect(service.releaseRequests, 1);
  });

  test('does not access camera APIs on unsupported platforms', () async {
    final unsupportedController = CameraRecorderController(
      service: service,
      isAndroidPlatform: false,
    );

    await unsupportedController.initialize();

    expect(unsupportedController.isLoading, isFalse);
    expect(service.permissionRequests, 0);
    expect(service.cameraListRequests, 0);
    await unsupportedController.close();
  });
}
