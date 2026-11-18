import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../app/coordinator.dart';
import '../../controllers/responder_controller.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/fmt.dart';
import '../../core/widgets/common.dart';
import '../../models/models.dart';
import '../../services/launcher.dart';
import 'mission_screen.dart';

/// Every request this responder got. A rejected or missed one can be taken again while nobody
/// else has accepted it. Finished ones open their chat history.
class ResponderHistoryScreen extends StatefulWidget {
  const ResponderHistoryScreen({super.key});

  @override
  State<ResponderHistoryScreen> createState() => _ResponderHistoryScreenState();
}

class _ResponderHistoryScreenState extends State<ResponderHistoryScreen> {
  @override
  void initState() {
    super.initState();
    ResponderController.I.loadHistory();
  }

  Future<void> _again(HistoryItem h) async {
    final ok = await confirm(
      context,
      title: 'Take this emergency?',
      message: 'Nobody has accepted it yet. It will be assigned to you right away.',
      yes: 'Accept',
      danger: false,
    );
    if (!ok || !mounted) return;
    final err = await ResponderController.I.reaccept(h.incidentId);
    if (!mounted) return;
    if (err != null) {
      toast(context, err, error: true);
      return;
    }
    Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => const MissionScreen()));
  }

  @override
  Widget build(BuildContext context) {
    final r = context.watch<ResponderController>();
    return Scaffold(
      appBar: AppBar(title: const Text('History')),
      body: RefreshIndicator(
        onRefresh: r.loadHistory,
        child: ListView(
          padding: const EdgeInsets.all(16),
          children: [
            if (r.historyLoading && r.history.isEmpty)
              const Center(
                child: Padding(padding: EdgeInsets.all(32), child: CircularProgressIndicator()),
              ),
            if (r.historyError != null)
              Panel(
                child: Text(r.historyError!, style: const TextStyle(color: AppColors.red)),
              ),
            if (!r.historyLoading && r.history.isEmpty && r.historyError == null)
              const Padding(
                padding: EdgeInsets.all(32),
                child: Text(
                  'No requests yet.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: AppColors.muted),
                ),
              ),
            for (final h in r.history) ...[
              _Row(h: h, onAgain: () => _again(h), busy: r.actionBusy),
              const SizedBox(height: 8),
            ],
          ],
        ),
      ),
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({required this.h, required this.onAgain, required this.busy});
  final HistoryItem h;
  final VoidCallback onAgain;
  final bool busy;

  @override
  Widget build(BuildContext context) {
    final pColor = AppColors.priority(Priority.level(h.priorityScore));
    return Panel(
      color: h.canAcceptAgain ? AppColors.red.withValues(alpha: 0.06) : null,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Pill(Priority.label(h.priorityScore), color: pColor),
              const SizedBox(width: 8),
              Expanded(
                child: Text(
                  h.driverName ?? 'Driver',
                  style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16),
                ),
              ),
              Text(Fmt.timeAgo(h.offeredAt), style: const TextStyle(color: AppColors.muted, fontSize: 12)),
            ],
          ),
          const SizedBox(height: 6),
          Text(
            '${Fmt.response(h.myResponse)} · ${Fmt.status(h.status)}',
            style: const TextStyle(color: AppColors.muted),
          ),
          const SizedBox(height: 10),
          Row(
            children: [
              if (h.canAcceptAgain)
                Expanded(
                  child: FilledButton.icon(
                    style: FilledButton.styleFrom(
                      backgroundColor: AppColors.green,
                      minimumSize: const Size.fromHeight(46),
                    ),
                    onPressed: busy ? null : onAgain,
                    icon: const Icon(Icons.replay_rounded),
                    label: const Text('Accept again'),
                  ),
                ),
              if (h.assignedToMe) ...[
                Expanded(
                  child: OutlinedButton.icon(
                    style: OutlinedButton.styleFrom(minimumSize: const Size.fromHeight(46)),
                    onPressed: () => AppCoordinator.I.openChat(h.incidentId, title: h.driverName),
                    icon: const Icon(Icons.chat_bubble_outline_rounded),
                    label: const Text('Messages'),
                  ),
                ),
              ],
              if (h.lat != null && h.lng != null) ...[
                const SizedBox(width: 8),
                IconButton.filledTonal(
                  tooltip: 'Directions',
                  onPressed: () => Launcher.directions(h.lat!, h.lng!),
                  icon: const Icon(Icons.navigation_rounded),
                ),
              ],
            ],
          ),
        ],
      ),
    );
  }
}
