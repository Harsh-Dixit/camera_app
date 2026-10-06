import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../camera/camera_recorder_controller.dart';
import '../camera/camera_service.dart';
import '../camera/capture_settings.dart';
import '../widgets/camera_card.dart';
import '../widgets/empty_camera_state.dart';

/// Presents camera previews and recording controls for Android devices.
class CameraRecorderScreen extends StatefulWidget {
  const CameraRecorderScreen({
    required this.cameraService,
    this.isAndroidPlatform,
    this.captureSettings = const CaptureSettings(),
    super.key,
  });

  final CameraService cameraService;
  final bool? isAndroidPlatform;
  final CaptureSettings captureSettings;

  @override
  State<CameraRecorderScreen> createState() => _CameraRecorderScreenState();
}

class _CameraRecorderScreenState extends State<CameraRecorderScreen> {
  late final CameraRecorderController _controller;
  Timer? _statusTimer;

  @override
  void initState() {
    super.initState();
    // Platform detection can be overridden when testing with a fake service.
    _controller = CameraRecorderController(
      service: widget.cameraService,
      settings: widget.captureSettings,
      isAndroidPlatform:
          widget.isAndroidPlatform ??
          defaultTargetPlatform == TargetPlatform.android,
    )..addListener(_onControllerChanged);
    unawaited(_controller.initialize());
    _statusTimer = Timer.periodic(const Duration(seconds: 1), (_) {
      if (!mounted) return;
      if (_controller.isBuffering &&
          !_controller.isCapturingSos &&
          !_controller.isBusy &&
          _controller.bufferingSeconds <
              _controller.settings.preEventDurationSeconds) {
        unawaited(_controller.refreshBufferingStatus());
      }
      if (_controller.isCapturingSos) setState(() {});
    });
  }

  @override
  void dispose() {
    _statusTimer?.cancel();
    _controller.removeListener(_onControllerChanged);
    unawaited(_controller.close());
    super.dispose();
  }

  void _onControllerChanged() {
    if (mounted) setState(() {});
  }

  /// Shows a responsive camera grid and a fixed recording control panel.
  @override
  Widget build(BuildContext context) {
    if (!_controller.isAndroidPlatform) {
      return const _UnsupportedPlatformScreen();
    }
    return Scaffold(
      appBar: AppBar(
        title: const Text(
          'Multi Camera',
          style: TextStyle(fontWeight: FontWeight.w700),
        ),
        actions: [
          IconButton(
            tooltip: 'Refresh cameras',
            onPressed: _controller.isBusy || _controller.isBuffering
                ? null
                : _controller.initialize,
            icon: const Icon(Icons.refresh_rounded),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: _controller.isLoading
            ? const Center(child: CircularProgressIndicator())
            : Column(
                children: [
                  Expanded(
                    child: _controller.cameras.isEmpty
                        ? EmptyCameraState(
                            message: _controller.message ?? 'No cameras found. Connect a supported USB camera and refresh.',
                            onRefresh: _controller.initialize,
                          )
                        : _cameraGrid(),
                  ),
                  _bottomPanel(),
                ],
              ),
      ),
    );
  }

  /// Uses two columns on wide layouts and one column on phone-sized layouts.
  Widget _cameraGrid() {
    return LayoutBuilder(
      builder: (context, constraints) {
        final columns = constraints.maxWidth >= 760 ? 2 : 1;
        return CustomScrollView(
          slivers: [
            SliverToBoxAdapter(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(20, 8, 20, 16),
                child: Row(
                  children: [
                    Expanded(
                      child: Text(
                        '${_controller.cameras.length} camera'
                        '${_controller.cameras.length == 1 ? '' : 's'} detected',
                        style: Theme.of(context).textTheme.titleMedium
                            ?.copyWith(fontWeight: FontWeight.w600),
                      ),
                    ),
                    TextButton.icon(
                      onPressed: _controller.isBusy || _controller.isBuffering
                          ? null
                          : _controller.toggleAllCameras,
                      icon: Icon(
                        _controller.selectedCameraIds.length ==
                                _controller.cameras.length
                            ? Icons.deselect_rounded
                            : Icons.select_all_rounded,
                      ),
                      label: Text(
                        _controller.selectedCameraIds.length ==
                                _controller.cameras.length
                            ? 'Clear'
                            : 'Select all',
                      ),
                    ),
                  ],
                ),
              ),
            ),
            SliverPadding(
              padding: const EdgeInsets.fromLTRB(20, 0, 20, 20),
              sliver: SliverGrid.builder(
                gridDelegate: SliverGridDelegateWithFixedCrossAxisCount(
                  crossAxisCount: columns,
                  mainAxisExtent: 312,
                  crossAxisSpacing: 14,
                  mainAxisSpacing: 14,
                ),
                itemCount: _controller.cameras.length,
                itemBuilder: (context, index) {
                  final camera = _controller.cameras[index];
                  final selected = _controller.selectedCameraIds.contains(
                    camera.id,
                  );
                  return CameraCard(
                    camera: camera,
                    selected: selected,
                    recording:
                        (_controller.isBuffering ||
                            _controller.isCapturingSos) &&
                        selected,
                    filePath: _controller.recordingPaths[camera.id],
                    onSelected: _controller.isBuffering || _controller.isBusy
                        ? null
                        : (value) => _controller.toggleCamera(camera.id, value),
                  );
                },
              ),
            ),
          ],
        );
      },
    );
  }

  /// Displays selection/recording status and the primary record/stop action.
  Widget _bottomPanel() {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.fromLTRB(20, 14, 20, 18),
      decoration: const BoxDecoration(
        color: Color(0xFF151719),
        border: Border(top: BorderSide(color: Color(0xFF292D30))),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        mainAxisSize: MainAxisSize.min,
        children: [
          if (_controller.message != null) ...[
            Text(
              _controller.message!,
              style: TextStyle(
                color: _controller.isCapturingSos
                    ? Theme.of(context).colorScheme.onSurfaceVariant
                    : const Color(0xFFFFA39F),
              ),
            ),
            const SizedBox(height: 12),
          ],
          Text(
            _controller.isBuffering
                ? _controller.isCapturingSos
                      ? 'SOS capture active · '
                            '${_controller.sosSecondsRemaining} seconds remaining'
                      : 'Rolling ${_controller.settings.segmentDurationSeconds}-second '
                            'segments · ${_controller.availablePreEventSeconds}/'
                            '${_controller.settings.preEventDurationSeconds} pre-event seconds'
                : '${_controller.selectedCameraIds.length} of '
                      '${_controller.cameras.length} cameras selected',
            textAlign: TextAlign.center,
            style: Theme.of(context).textTheme.bodyMedium?.copyWith(
              color: Theme.of(context).colorScheme.onSurfaceVariant,
            ),
          ),
          const SizedBox(height: 12),
          if (!_controller.isBuffering)
            FilledButton.icon(
              onPressed:
                  _controller.isBusy ||
                      _controller.cameras.isEmpty ||
                      _controller.selectedCameraIds.isEmpty
                  ? null
                  : _controller.startBuffering,
              style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFF3B4145),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 15),
              ),
              icon: _controller.isBusy
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Icon(Icons.videocam_rounded),
              label: Text(
                _controller.isBusy
                    ? 'Starting cameras'
                    : 'Start SOS monitoring',
                style: const TextStyle(fontWeight: FontWeight.w700),
              ),
            )
          else ...[
            FilledButton.icon(
              onPressed: _controller.isBusy || !_controller.isSosReady
                  ? null
                  : _controller.triggerSos,
              style: FilledButton.styleFrom(
                backgroundColor: const Color(0xFFFF3939),
                foregroundColor: Colors.white,
                padding: const EdgeInsets.symmetric(vertical: 16),
              ),
              icon: _controller.isCapturingSos
                  ? const SizedBox.square(
                      dimension: 18,
                      child: CircularProgressIndicator(
                        strokeWidth: 2,
                        color: Colors.white,
                      ),
                    )
                  : const Icon(Icons.sos_rounded),
              label: Text(
                _controller.isCapturingSos
                    ? 'SAVING SOS · ${_controller.sosSecondsRemaining}s'
                    : 'SOS · ${_controller.availablePreEventSeconds}s BEFORE + '
                          '${_controller.settings.postEventDurationSeconds}s AFTER',
                style: const TextStyle(
                  fontWeight: FontWeight.w800,
                  letterSpacing: 0.4,
                ),
              ),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: _controller.isBusy ? null : _controller.stopBuffering,
              child: const Text('Stop monitoring and discard buffer'),
            ),
          ],
        ],
      ),
    );
  }
}

class _UnsupportedPlatformScreen extends StatelessWidget {
  const _UnsupportedPlatformScreen();

  @override
  Widget build(BuildContext context) {
    return const Scaffold(
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: EdgeInsets.all(32),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.phone_android_rounded, size: 48),
                SizedBox(height: 16),
                Text(
                  'Camera recording is currently implemented for Android. '
                  'USB camera access and simultaneous mobile cameras are '
                  'hardware-dependent.',
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
