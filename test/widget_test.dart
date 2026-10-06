import 'package:camera_app/src/app.dart';
import 'package:camera_app/src/camera/capture_settings.dart';
import 'package:camera_app/src/widgets/camera_card.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';

import 'support/fake_camera_service.dart';

/// Exercises the visible selection/recording flow without physical cameras.
void main() {
  testWidgets(
    'arms cameras, triggers SOS, and saves a separate clip per camera',
    (tester) async {
      final service = FakeCameraService();
      await tester.pumpWidget(
        MultiCameraRecorderApp(
          cameraService: service,
          isAndroidPlatform: true,
          captureSettings: const CaptureSettings(combinedVideoEnabled: true),
        ),
      );
      await tester.pumpAndSettle();

      expect(
        find.text('3 available cameras · phone and Camera2 USB devices'),
        findsOneWidget,
      );
      expect(find.text('0 of 3 cameras selected'), findsOneWidget);

      await tester.tap(find.text('Select all'));
      await tester.pumpAndSettle();
      expect(find.text('3 of 3 cameras selected'), findsOneWidget);
      expect(service.previewSelections.single.toSet(), {
        'front',
        'back',
        'usb',
      });
      expect(find.text('USB / external camera'), findsOneWidget);

      await tester.tap(find.text('Start SOS monitoring'));
      await tester.pumpAndSettle();
      expect(find.textContaining('30/30 pre-event seconds'), findsOneWidget);
      expect(find.byType(CameraCard), findsAtLeastNWidgets(1));
      expect(service.bufferingSelections.single.toSet(), {
        'front',
        'back',
        'usb',
      });

      await tester.tap(find.text('SOS · 30s BEFORE + 30s AFTER'));
      await tester.pumpAndSettle();
      expect(
        find.textContaining(
          'SOS #1 saved: 3 separate videos and combined front/back video:',
        ),
        findsOneWidget,
      );
      expect(
        find.textContaining('/movies/camera_combined.mp4'),
        findsOneWidget,
      );
      expect(find.byType(CameraCard), findsAtLeastNWidgets(1));
      expect(
        find.textContaining('Saved: /movies/camera_front.mp4'),
        findsOneWidget,
      );
      expect(
        find.textContaining('Saved: /movies/camera_back.mp4'),
        findsOneWidget,
      );
      final usbVideoPath = find.textContaining('Saved: /movies/camera_usb.mp4');
      await tester.scrollUntilVisible(
        usbVideoPath,
        300,
        scrollable: find.byType(Scrollable).first,
      );
      await tester.pumpAndSettle();
      expect(usbVideoPath, findsOneWidget);

      for (var capture = 0; capture < 2; capture++) {
        await tester.tap(find.text('SOS · 30s BEFORE + 30s AFTER'));
        await tester.pumpAndSettle();
      }
      expect(service.sosRequests, 3);
      expect(
        find.textContaining('Saved 3 SOS captures · latest:'),
        findsOneWidget,
      );
    },
  );

  testWidgets('explains when native camera recording is unsupported', (
    tester,
  ) async {
    await tester.pumpWidget(
      MultiCameraRecorderApp(
        cameraService: FakeCameraService(),
        isAndroidPlatform: false,
      ),
    );

    expect(
      find.textContaining('currently implemented for Android'),
      findsOneWidget,
    );
    expect(find.textContaining('hardware-dependent'), findsOneWidget);
  });

  testWidgets('allows SOS while the pre-event buffer is still warming', (
    tester,
  ) async {
    final service = FakeCameraService()..bufferingSeconds = 8;
    await tester.pumpWidget(
      MultiCameraRecorderApp(cameraService: service, isAndroidPlatform: true),
    );
    await tester.pumpAndSettle();
    await tester.tap(find.text('Select all'));
    await tester.pumpAndSettle();
    await tester.tap(find.text('Start SOS monitoring'));
    await tester.pumpAndSettle();

    final sosLabel = find.text('SOS · 8s BEFORE + 30s AFTER');
    expect(sosLabel, findsOneWidget);
    final sosButton = find.ancestor(
      of: sosLabel,
      matching: find.byType(FilledButton),
    );
    expect(tester.widget<FilledButton>(sosButton).onPressed, isNotNull);
    expect(find.textContaining('8/30 pre-event seconds'), findsOneWidget);
    expect(
      find.textContaining('8 seconds before + 30 seconds after'),
      findsOneWidget,
    );
    await tester.tap(sosLabel);
    await tester.pumpAndSettle();
    expect(service.sosRequests, 1);
  });
}
