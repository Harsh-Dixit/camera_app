import 'package:flutter/material.dart';

import '../platform/camera_api.g.dart';

/// Shows one camera preview, its selection control, and its output file path.
class CameraCard extends StatelessWidget {
  const CameraCard({
    required this.camera,
    required this.selected,
    required this.recording,
    required this.filePath,
    required this.onSelected,
    super.key,
  });

  final CameraDeviceInfo camera;
  final bool selected;
  final bool recording;
  final String? filePath;
  final ValueChanged<bool>? onSelected;

  /// Renders the texture only while selected to avoid opening unused cameras.
  @override
  Widget build(BuildContext context) {
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Expanded(
            child: Stack(
              fit: StackFit.expand,
              children: [
                ColoredBox(
                  color: const Color(0xFF080909),
                  child: selected
                      ? Texture(textureId: camera.previewTextureId)
                      : const Center(
                          child: Icon(
                            Icons.videocam_outlined,
                            size: 38,
                            color: Color(0xFF667075),
                          ),
                        ),
                ),
                if (recording)
                  const Positioned(
                    left: 12,
                    top: 12,
                    child: _RecordingBadge(),
                  ),
              ],
            ),
          ),
          CheckboxListTile(
            value: selected,
            onChanged: onSelected == null
                ? null
                : (value) => onSelected!(value ?? false),
            title: Text(
              camera.name,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
              style: const TextStyle(fontWeight: FontWeight.w600),
            ),
            subtitle: Text(
              camera.isExternal ? 'USB / external camera' : camera.facing,
              maxLines: 1,
              overflow: TextOverflow.ellipsis,
            ),
            secondary: Icon(
              camera.isExternal ? Icons.usb_rounded : Icons.camera_alt_outlined,
            ),
            controlAffinity: ListTileControlAffinity.trailing,
            contentPadding: const EdgeInsets.fromLTRB(12, 0, 8, 0),
          ),
          if (filePath != null)
            Padding(
              padding: const EdgeInsets.fromLTRB(14, 0, 14, 12),
              child: Text(
                'Saved: $filePath',
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: Theme.of(context).textTheme.labelSmall?.copyWith(
                      color: Theme.of(context).colorScheme.onSurfaceVariant,
                    ),
              ),
            ),
        ],
      ),
    );
  }
}

class _RecordingBadge extends StatelessWidget {
  const _RecordingBadge();

  @override
  Widget build(BuildContext context) {
    return DecoratedBox(
      decoration: BoxDecoration(
        color: const Color(0xFFD8433D),
        borderRadius: BorderRadius.circular(6),
      ),
      child: const Padding(
        padding: EdgeInsets.symmetric(horizontal: 9, vertical: 5),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(Icons.circle, size: 8),
            SizedBox(width: 6),
            Text(
              'REC',
              style: TextStyle(
                fontSize: 11,
                fontWeight: FontWeight.w800,
                letterSpacing: 0.7,
              ),
            ),
          ],
        ),
      ),
    );
  }
}
