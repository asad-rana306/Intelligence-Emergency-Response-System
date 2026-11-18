import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../services/duty_service.dart';

/// Put this once at the top of the responder's home page.
///
///  - While the responder is ON duty it shows the time left in the 1-hour shift
///    ("On duty — 48m 12s left"), with a "Renew" button in the last 10 minutes.
///  - When the server puts the responder OFF duty by itself (the hour is over, or the phone was
///    silent) it opens a dialog with a "Go on duty again" button. The 5-second sound and the
///    notification come from DutyService / LocalAlerts, so they also work when this page is closed.
class OffDutyWatcher extends StatefulWidget {
  const OffDutyWatcher({super.key, required this.onGoOnDutyAgain, required this.onRenew});

  /// "Go on duty again" in the dialog.
  final VoidCallback onGoOnDutyAgain;

  /// "Renew" in the banner (starts a new 1-hour shift).
  final VoidCallback onRenew;

  @override
  State<OffDutyWatcher> createState() => _OffDutyWatcherState();
}

class _OffDutyWatcherState extends State<OffDutyWatcher> {
  bool _dialogOpen = false;

  @override
  Widget build(BuildContext context) {
    final duty = context.watch<DutyService>();
    if (duty.wasAutoOffDuty && !_dialogOpen) {
      WidgetsBinding.instance.addPostFrameCallback((_) => _showDialog(duty));
    }

    if (duty.status != DutyService.onDuty || duty.secondsLeft <= 0) return const SizedBox.shrink();
    final soon = duty.secondsLeft < 600;
    return Material(
      color: soon ? const Color(0xFFFEE4E2) : const Color(0xFFFFF4E5),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(16, 6, 8, 6),
        child: Row(
          children: [
            Icon(Icons.timer_outlined, color: soon ? const Color(0xFFB42318) : const Color(0xFFB54708), size: 20),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                'On duty — ${duty.formattedTimeLeft} left',
                style: TextStyle(
                  color: soon ? const Color(0xFFB42318) : const Color(0xFFB54708),
                  fontWeight: FontWeight.w700,
                ),
              ),
            ),
            if (soon) TextButton(onPressed: widget.onRenew, child: const Text('Renew')),
          ],
        ),
      ),
    );
  }

  Future<void> _showDialog(DutyService duty) async {
    if (!mounted || _dialogOpen || !duty.wasAutoOffDuty) return;
    _dialogOpen = true;
    final reason = duty.autoOffReason;
    duty.ackAutoOffDuty();

    final why = reason == 'HEARTBEAT_LOST'
        ? 'The server did not hear from this phone for 2 minutes.'
        : 'Your 1-hour shift is over.';
    final again = await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (c) => AlertDialog(
        title: const Text('You are now OFF duty'),
        content: Text('$why You will not get new emergency requests until you go on duty again.'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Later')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Go on duty again')),
        ],
      ),
    );
    _dialogOpen = false;
    if (again == true) widget.onGoOnDutyAgain();
  }
}
