import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../app/coordinator.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/fmt.dart';
import '../../core/widgets/common.dart';
import '../../services/local_history.dart';

class DriverHistoryScreen extends StatelessWidget {
  const DriverHistoryScreen({super.key});

  static (IconData, Color) _look(String kind) {
    switch (kind) {
      case 'CRASH':
        return (Icons.car_crash_rounded, AppColors.red);
      case 'SOS':
        return (Icons.sos_rounded, AppColors.red);
      case 'CANCEL':
        return (Icons.cancel_rounded, AppColors.muted);
      case 'HELP':
        return (Icons.local_hospital_rounded, AppColors.blue);
      case 'DONE':
        return (Icons.verified_rounded, AppColors.green);
      default:
        return (Icons.info_rounded, AppColors.muted);
    }
  }

  @override
  Widget build(BuildContext context) {
    final h = context.watch<LocalHistory>();
    return Scaffold(
      appBar: AppBar(
        title: const Text('History'),
        actions: [
          if (h.entries.isNotEmpty)
            IconButton(
              tooltip: 'Clear',
              onPressed: () async {
                if (await confirm(
                  context,
                  title: 'Clear history?',
                  message: 'This removes the list from this phone.',
                  yes: 'Clear',
                )) {
                  LocalHistory.I.clear();
                }
              },
              icon: const Icon(Icons.delete_sweep_rounded),
            ),
        ],
      ),
      body: h.entries.isEmpty
          ? const Center(
              child: Text('Nothing here yet. Drive safe!', style: TextStyle(color: AppColors.muted)),
            )
          : ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: h.entries.length,
              separatorBuilder: (_, _) => const SizedBox(height: 8),
              itemBuilder: (_, i) {
                final e = h.entries[i];
                final (icon, color) = _look(e.kind);
                return Panel(
                  padding: const EdgeInsets.all(12),
                  onTap: e.incidentId == null ? null : () => AppCoordinator.I.openChat(e.incidentId!),
                  child: Row(
                    children: [
                      Avatar(null, icon: icon, color: color, size: 42),
                      const SizedBox(width: 12),
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Text(e.title, style: const TextStyle(fontWeight: FontWeight.w800)),
                            Text(
                              [Fmt.dateTime(e.at), if (e.detail != null && e.detail!.isNotEmpty) e.detail!].join(' · '),
                              style: const TextStyle(color: AppColors.muted, fontSize: 13),
                            ),
                          ],
                        ),
                      ),
                      if (e.incidentId != null)
                        const Icon(Icons.chat_bubble_outline_rounded, color: AppColors.muted, size: 18),
                    ],
                  ),
                );
              },
            ),
    );
  }
}
