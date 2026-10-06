import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../camera/camera_recorder_controller.dart';
import '../widgets/camera_card.dart';
import '../widgets/empty_camera_state.dart';

/// Presents camera previews and recording controls for Android devices.
class CameraRecorderScreen extends StatefulWidget {
  const CameraRecorderScreen({super.key});

  @override
  State<CameraRecorderScreen> createState() => _CameraRecorderScreenState();
}

class _CameraRecorderScreenState extends State<CameraRecorderScreen> {
  Timer? _statusTimer;

  /// Reads shared camera state for event handlers that do not rebuild the UI.
  CameraRecorderController get _controller =>
      context.read<CameraRecorderController>();

  /// Starts periodic polling for pre-roll and save progress.
  @override
  void initState() {
    super.initState();
    _statusTimer = Timer.periodic(const Duration(milliseconds: 500), (_) {
      if (!mounted) return;
      if (_controller.isBuffering &&
          !_controller.isCapturingSos &&
          !_controller.isBusy &&
          _controller.bufferingSeconds <
              _controller.settings.preEventDurationSeconds) {
        unawaited(_controller.refreshBufferingStatus());
      }
      if (_controller.isCapturingSos) {
        unawaited(_controller.refreshSosProgress());
      }
    });
  }

  /// Stops the screen timer; Provider releases the shared controller separately.
  @override
  void dispose() {
    _statusTimer?.cancel();
    super.dispose();
  }

  /// Shows a responsive camera grid and a fixed recording control panel.
  @override
  Widget build(BuildContext context) {
    final controller = context.watch<CameraRecorderController>();
    if (!controller.isAndroidPlatform) {
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
            tooltip: 'Refresh available phone and USB cameras',
            onPressed: _controller.isBusy || _controller.isBuffering
                ? null
                : _controller.initialize,
            icon: const Icon(Icons.refresh_rounded),
          ),
          const SizedBox(width: 8),
        ],
      ),
      body: SafeArea(
        child: controller.isLoading
            ? const Center(child: CircularProgressIndicator())
            : Column(
                children: [
                  Expanded(
                    child: controller.cameras.isEmpty
                        ? EmptyCameraState(
                            message: controller.message ?? 'No cameras found. Connect a supported USB camera and refresh.',
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
                        '${_controller.cameras.length} available camera'
                        '${_controller.cameras.length == 1 ? '' : 's'} · '
                            'phone and Camera2 USB devices',
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
                      ? _controller.sosSecondsRemaining > 0
                            ? 'SOS capture active · '
                                  '${_controller.sosSecondsRemaining} seconds remaining'
                            : 'Saving SOS videos…'
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
          if (_controller.recordingHistory.isNotEmpty) ...[
            const SizedBox(height: 8),
            Text(
              'Saved ${_controller.recordingHistory.length} SOS capture'
              '${_controller.recordingHistory.length == 1 ? '' : 's'} · latest: '
              '${_controller.recordingHistory.last.map((recording) => recording.cameraId).join(', ')}',
              textAlign: TextAlign.center,
              maxLines: 2,
              overflow: TextOverflow.ellipsis,
              style: Theme.of(context).textTheme.labelMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
          if (_controller.isCapturingSos) ...[
            const SizedBox(height: 10),
            LinearProgressIndicator(
              value: _controller.sosProgress / 100,
              minHeight: 6,
              borderRadius: BorderRadius.circular(8),
              semanticsLabel: 'SOS video saving progress',
            ),
            const SizedBox(height: 5),
            Text(
              '${_controller.sosProgress}% · Saving camera videos',
              textAlign: TextAlign.center,
              style: Theme.of(context).textTheme.labelMedium?.copyWith(
                color: Theme.of(context).colorScheme.onSurfaceVariant,
              ),
            ),
          ],
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
                    ? 'SAVING SOS · ${_controller.sosProgress}%'
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

  /// Explains that native camera recording is currently Android-only.
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
