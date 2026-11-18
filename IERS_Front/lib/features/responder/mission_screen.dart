import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../../app/coordinator.dart';
import '../../controllers/responder_controller.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/fmt.dart';
import '../../core/utils/geo.dart';
import '../../core/widgets/common.dart';
import '../../core/widgets/map_view.dart';
import '../../models/models.dart';
import '../../services/call_service.dart';
import '../../services/chat_service.dart';
import '../../services/launcher.dart';

/// The accepted emergency: where to go, who the patient is, how to reach them, next step.
class MissionScreen extends StatefulWidget {
  const MissionScreen({super.key});

  @override
  State<MissionScreen> createState() => _MissionScreenState();
}

class _MissionScreenState extends State<MissionScreen> {
  bool _closing = false;
  @override
  void initState() {
    super.initState();
    WakelockPlus.enable();
  }

  @override
  void dispose() {
    WakelockPlus.disable();
    super.dispose();
  }

  Future<void> _step(String next, {String? confirmText}) async {
    if (confirmText != null) {
      final ok = await confirm(context, title: 'Are you sure?', message: confirmText, yes: 'Yes', danger: false);
      if (!ok) return;
    }
    String? hospital;
    if (next == 'EN_ROUTE_HOSPITAL') {
      hospital = await _askHospital();
      if (hospital == null) return;
    }
    final err = await ResponderController.I.setStatus(next, hospital: hospital);
    if (err != null && mounted) toast(context, err, error: true);
  }

  Future<String?> _askHospital() {
    final c = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (d) => AlertDialog(
        title: const Text('Which hospital?'),
        content: TextField(
          controller: c,
          autofocus: true,
          decoration: const InputDecoration(hintText: 'e.g. Mayo Hospital (optional)'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(d), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(d, c.text.trim()), child: const Text('Go')),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final r = context.watch<ResponderController>();
    final m = r.mission;
    if (m == null) {
      if (!_closing) {
        _closing = true;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) closeOwnPage(context);
        });
      }
      return const Scaffold(body: SizedBox());
    }
    final unread = context.watch<ChatService>().unreadFor(m.id);
    final dist = r.distanceToCrash;
    final pColor = AppColors.priority(Priority.level(m.priorityScore));

    return Scaffold(
      appBar: AppBar(
        title: Text(Fmt.status(m.status)),
        actions: [
          IconButton(
            tooltip: 'Hospitals nearby',
            onPressed: () => Launcher.nearbyHospitals(m.lat, m.lng),
            icon: const Icon(Icons.local_hospital_rounded),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        children: [
          SizedBox(
            height: 240,
            child: Stack(
              children: [
                Positioned.fill(
                  child: MapView(
                    borderRadius: 20,
                    pins: [
                      if (m.lat != null && m.lng != null)
                        MapPin(m.lat!, m.lng!, icon: Icons.car_crash_rounded, label: 'Patient'),
                      if (r.myLat != null && r.myLng != null)
                        MapPin(
                          r.myLat!,
                          r.myLng!,
                          icon: Icons.local_shipping_rounded,
                          color: AppColors.blue,
                          label: 'You',
                        ),
                    ],
                  ),
                ),
                if (m.lat != null && m.lng != null)
                  Positioned(
                    right: 12,
                    bottom: 12,
                    child: FilledButton.icon(
                      style: FilledButton.styleFrom(backgroundColor: AppColors.blue, minimumSize: const Size(0, 52)),
                      onPressed: () => Launcher.directions(m.lat!, m.lng!),
                      icon: const Icon(Icons.navigation_rounded),
                      label: const Text('Directions'),
                    ),
                  ),
              ],
            ),
          ),
          if (dist != null) ...[
            const SizedBox(height: 8),
            Text(
              '${Geo.distanceText(dist)} away · ${Geo.etaText(Geo.roughEta(dist))}',
              style: const TextStyle(fontWeight: FontWeight.w800),
            ),
          ],
          const SizedBox(height: 12),

          if (r.bystander)
            Padding(
              padding: const EdgeInsets.only(bottom: 12),
              child: Panel(
                color: AppColors.amber.withValues(alpha: 0.18),
                child: const Row(
                  children: [
                    Icon(Icons.front_hand_rounded, color: Color(0xFFB45309)),
                    SizedBox(width: 10),
                    Expanded(
                      child: Text(
                        'A bystander is with the patient. Call to talk to them.',
                        style: TextStyle(fontWeight: FontWeight.w800),
                      ),
                    ),
                  ],
                ),
              ),
            ),

          Panel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    Avatar(m.driverName, color: AppColors.red, size: 52),
                    const SizedBox(width: 12),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text(m.driverName ?? 'Patient', style: Theme.of(context).textTheme.titleLarge),
                          if ((m.driverPhone ?? '').isNotEmpty)
                            Text(m.driverPhone!, style: const TextStyle(color: AppColors.muted)),
                        ],
                      ),
                    ),
                    Pill(Priority.label(m.priorityScore), color: pColor, filled: true),
                  ],
                ),
                const SizedBox(height: 10),
                Wrap(
                  spacing: 8,
                  runSpacing: 8,
                  children: [
                    Pill(
                      'Blood ${(m.bloodType ?? '').isEmpty ? 'unknown' : m.bloodType}',
                      color: AppColors.red,
                      icon: Icons.bloodtype_rounded,
                    ),
                    if ((m.allergies ?? '').isNotEmpty && m.allergies != 'UNKNOWN')
                      Pill('Allergy: ${m.allergies}', color: const Color(0xFFB45309)),
                  ],
                ),
                const SizedBox(height: 14),
                Row(
                  children: [
                    Expanded(
                      child: ActionTile(
                        icon: Icons.call_rounded,
                        label: 'Call',
                        caption: 'internet',
                        color: Colors.white,
                        background: AppColors.green,
                        onTap: () =>
                            CallService.I.call(incidentId: m.id, name: m.driverName ?? 'Patient', phone: m.driverPhone),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: ActionTile(
                        icon: Icons.phone_in_talk_rounded,
                        label: 'Phone',
                        caption: 'no internet',
                        color: AppColors.blue,
                        onTap: (m.driverPhone ?? '').isEmpty ? null : () => Launcher.callNow(m.driverPhone!),
                      ),
                    ),
                    const SizedBox(width: 8),
                    Expanded(
                      child: ActionTile(
                        icon: Icons.chat_rounded,
                        label: 'Message',
                        caption: 'text / voice',
                        badge: unread,
                        onTap: () => AppCoordinator.I.openChat(m.id, title: m.driverName),
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
          const SizedBox(height: 20),

          ..._nextSteps(m.status, r.actionBusy),
        ],
      ),
    );
  }

  List<Widget> _nextSteps(String status, bool busy) {
    Widget big(String text, IconData icon, Color color, VoidCallback onTap) => SizedBox(
      height: 64,
      child: FilledButton.icon(
        style: FilledButton.styleFrom(backgroundColor: color),
        onPressed: busy ? null : onTap,
        icon: Icon(icon),
        label: Text(text, style: const TextStyle(fontSize: 18)),
      ),
    );
    switch (status) {
      case 'ACCEPTED':
        return [big('I have arrived', Icons.place_rounded, AppColors.blue, () => _step('ARRIVED'))];
      case 'ARRIVED':
        return [
          big('Take to hospital', Icons.local_hospital_rounded, AppColors.red, () => _step('EN_ROUTE_HOSPITAL')),
          const SizedBox(height: 10),
          OutlinedButton.icon(
            onPressed: busy
                ? null
                : () => _step('RESOLVED', confirmText: 'Treated on site, no hospital needed. Complete the mission?'),
            icon: const Icon(Icons.check_rounded),
            label: const Text('Treated here — complete'),
          ),
        ];
      case 'EN_ROUTE_HOSPITAL':
        return [
          big(
            'Handed over — complete',
            Icons.verified_rounded,
            AppColors.green,
            () => _step('RESOLVED', confirmText: 'The patient is with the hospital. Complete the mission?'),
          ),
        ];
      default:
        return const [];
    }
  }
}
