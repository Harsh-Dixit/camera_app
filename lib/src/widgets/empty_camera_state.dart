import 'package:flutter/material.dart';

/// Empty or permission-error state with an action to retry camera discovery.
class EmptyCameraState extends StatelessWidget {
  const EmptyCameraState({
    required this.message,
    required this.onRefresh,
    super.key,
  });

  final String message;
  final VoidCallback onRefresh;

  @override
  Widget build(BuildContext context) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.all(32),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Icon(
              Icons.no_photography_outlined,
              size: 52,
              color: Color(0xFF879196),
            ),
            const SizedBox(height: 16),
            Text(message, textAlign: TextAlign.center),
            const SizedBox(height: 16),
            OutlinedButton.icon(
              onPressed: onRefresh,
              icon: const Icon(Icons.refresh_rounded),
              label: const Text('Refresh cameras'),
            ),
          ],
        ),
      ),
    );
  }
}
