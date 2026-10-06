import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'camera/capture_settings.dart';
import 'camera/camera_recorder_controller.dart';
import 'camera/camera_service.dart';
import 'screens/camera_recorder_screen.dart';

/// Configures the app theme and provides camera state to the screen tree.
///
/// A fake [cameraService] and explicit platform flag can be supplied in tests
/// so the UI can be exercised without opening a physical camera.
class MultiCameraRecorderApp extends StatelessWidget {
  const MultiCameraRecorderApp({
    super.key,
    this.cameraService,
    this.isAndroidPlatform,
    this.captureSettings = const CaptureSettings(),
  });

  final CameraService? cameraService;
  final bool? isAndroidPlatform;
  final CaptureSettings captureSettings;

  /// Creates the shared camera controller and exposes it to Provider consumers.
  @override
  Widget build(BuildContext context) {
    const accent = Color(0xFFFF5B55);
    final cameraService = this.cameraService ?? PigeonCameraService();
    return MaterialApp(
      title: 'Multi Camera Recorder',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(
        brightness: Brightness.dark,
        colorScheme: ColorScheme.fromSeed(
          seedColor: accent,
          brightness: Brightness.dark,
          surface: const Color(0xFF151719),
        ),
        scaffoldBackgroundColor: const Color(0xFF0E1011),
        appBarTheme: const AppBarTheme(
          backgroundColor: Color(0xFF0E1011),
          foregroundColor: Colors.white,
          centerTitle: false,
        ),
        cardTheme: const CardThemeData(
          color: Color(0xFF181B1D),
          margin: EdgeInsets.zero,
        ),
        useMaterial3: true,
      ),
      home: ChangeNotifierProvider<CameraRecorderController>(
        create: (_) => CameraRecorderController(
          service: cameraService,
          isAndroidPlatform:
              isAndroidPlatform ??
              defaultTargetPlatform == TargetPlatform.android,
          settings: captureSettings,
        )..initialize(),
        child: const CameraRecorderScreen(),
      ),
    );
  }
}
