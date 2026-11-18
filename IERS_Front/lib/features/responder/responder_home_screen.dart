import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../app/coordinator.dart';
import '../../controllers/responder_controller.dart';
import '../../core/net/stomp_socket.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/fmt.dart';
import '../../core/widgets/common.dart';
import '../../core/widgets/map_view.dart';
import '../../services/auth_service.dart';
import '../../services/launcher.dart';
import '../../services/live_service.dart';
import '../common/saved_screen.dart';
import '../settings/settings_screen.dart';
import 'mission_screen.dart';
import 'off_duty_watcher.dart';
import 'responder_history_screen.dart';

class ResponderHomeScreen extends StatefulWidget {
  const ResponderHomeScreen({super.key});

  @override
  State<ResponderHomeScreen> createState() => _ResponderHomeScreenState();
}

class _ResponderHomeScreenState extends State<ResponderHomeScreen> {
  @override
  void initState() {
    super.initState();
    ResponderController.I.loadHistory();
  }

  Future<void> _setDuty(String s, {bool force = false}) async {
    final err = await ResponderController.I.setDuty(s, force: force);
    if (err != null && mounted) toast(context, err, error: true);
  }

  @override
  Widget build(BuildContext context) {
    final r = context.watch<ResponderController>();
    final auth = context.watch<AuthService>();
    final today = r.history
        .where(
          (h) =>
              h.myResponse == 'ACCEPTED' &&
              h.offeredAt != null &&
              DateTime.now().difference(h.offeredAt!) < const Duration(hours: 24),
        )
        .length;

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(auth.fullName ?? 'Responder'),
            Text(
              auth.vehicleId ?? 'Rescue unit',
              style: const TextStyle(fontSize: 13, color: AppColors.muted, fontWeight: FontWeight.w600),
            ),
          ],
        ),
        actions: [
          ValueListenableBuilder<SocketState>(
            valueListenable: LiveService.I.state,
            builder: (_, s, _) => Padding(
              padding: const EdgeInsets.symmetric(horizontal: 6),
              child: Icon(Icons.circle, size: 12, color: s == SocketState.online ? AppColors.green : AppColors.amber),
            ),
          ),
          IconButton(
            onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SettingsScreen())),
            icon: const Icon(Icons.settings_rounded),
          ),
        ],
      ),
      body: Column(
        children: [
          // Time left in the 1-hour shift, and the "you are OFF duty" dialog.
          OffDutyWatcher(
            onGoOnDutyAgain: () => _setDuty(Duty.onDuty),
            onRenew: () => _setDuty(Duty.onDuty, force: true),
          ),
          ValueListenableBuilder<SocketState>(
            valueListenable: LiveService.I.state,
            builder: (_, s, _) => ConnectionStrip(
              online: s == SocketState.online || s == SocketState.off,
              label: 'Reconnecting… new requests arrive when the connection is back.',
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () async {
                await r.restore();
                await r.loadHistory();
              },
              child: ListView(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
                children: [
                  if (r.notice != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Panel(
                        color: AppColors.blue.withValues(alpha: 0.1),
                        child: Row(
                          children: [
                            const Icon(Icons.info_rounded, color: AppColors.blue),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(r.notice!, style: const TextStyle(fontWeight: FontWeight.w600)),
                            ),
                            IconButton(onPressed: r.clearNotice, icon: const Icon(Icons.close_rounded, size: 18)),
                          ],
                        ),
                      ),
                    ),
                  if (r.mission != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Panel(
                        color: AppColors.green,
                        onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const MissionScreen())),
                        child: Row(
                          children: [
                            const Icon(Icons.local_hospital_rounded, color: Colors.white, size: 30),
                            const SizedBox(width: 12),
                            Expanded(
                              child: Column(
                                crossAxisAlignment: CrossAxisAlignment.start,
                                children: [
                                  const Text(
                                    'Current emergency',
                                    style: TextStyle(color: Colors.white70, fontWeight: FontWeight.w700),
                                  ),
                                  Text(
                                    '${r.mission!.driverName ?? 'Patient'} · ${Fmt.status(r.mission!.status)}',
                                    style: const TextStyle(
                                      color: Colors.white,
                                      fontSize: 18,
                                      fontWeight: FontWeight.w900,
                                    ),
                                  ),
                                ],
                              ),
                            ),
                            const Icon(Icons.chevron_right_rounded, color: Colors.white),
                          ],
                        ),
                      ),
                    ),
                  if (r.offer != null && r.incomingOffer.value == null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Panel(
                        color: AppColors.red,
                        // The app opens the request page when this is set.
                        onTap: () => r.incomingOffer.value = r.offer,
                        child: const Row(
                          children: [
                            Icon(Icons.notifications_active_rounded, color: Colors.white),
                            SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                'A request is waiting for you — open',
                                style: TextStyle(color: Colors.white, fontWeight: FontWeight.w900, fontSize: 16),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ),

                  const SectionTitle('My status'),
                  Row(
                    children: [
                      _DutyCard(
                        label: 'On duty',
                        hint: 'Get requests',
                        icon: Icons.check_circle_rounded,
                        color: AppColors.green,
                        selected: r.duty == Duty.onDuty,
                        busy: r.dutyChanging,
                        onTap: () => _setDuty(Duty.onDuty),
                      ),
                      const SizedBox(width: 8),
                      _DutyCard(
                        label: 'Busy',
                        hint: r.onMission ? 'On a mission' : 'Other emergency',
                        icon: Icons.timelapse_rounded,
                        color: AppColors.amber,
                        selected: r.duty == Duty.busy,
                        busy: r.dutyChanging,
                        onTap: () => _setDuty(Duty.busy),
                      ),
                      const SizedBox(width: 8),
                      _DutyCard(
                        label: 'Off duty',
                        hint: 'No requests',
                        icon: Icons.do_not_disturb_on_rounded,
                        color: AppColors.muted,
                        selected: r.duty == Duty.offDuty,
                        busy: r.dutyChanging,
                        onTap: () => _setDuty(Duty.offDuty),
                      ),
                    ],
                  ),

                  const SectionTitle('My location'),
                  if (!r.locationOk)
                    const Panel(
                      child: Text('Location is off. Turn it on so Dispatch can send you the nearest emergencies.'),
                    )
                  else
                    SizedBox(
                      height: 200,
                      child: MapView(
                        borderRadius: 20,
                        pins: [
                          if (r.myLat != null && r.myLng != null)
                            MapPin(
                              r.myLat!,
                              r.myLng!,
                              icon: Icons.local_shipping_rounded,
                              color: AppColors.blue,
                              label: 'Me',
                            ),
                        ],
                      ),
                    ),

                  const SectionTitle('Today'),
                  Row(
                    children: [
                      Expanded(
                        child: _Stat(value: '$today', label: 'accepted (24 h)'),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: _Stat(value: '${r.history.length}', label: 'requests in history'),
                      ),
                    ],
                  ),

                  const SizedBox(height: 16),
                  Row(
                    children: [
                      Expanded(
                        child: ActionTile(
                          icon: Icons.history_rounded,
                          label: 'History',
                          caption: r.history.any((h) => h.canAcceptAgain) ? 'open requests!' : 'all requests',
                          color: r.history.any((h) => h.canAcceptAgain) ? AppColors.red : AppColors.ink,
                          onTap: () => Navigator.push(
                            context,
                            MaterialPageRoute(builder: (_) => const ResponderHistoryScreen()),
                          ),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: ActionTile(
                          icon: Icons.bookmark_rounded,
                          label: 'Saved',
                          caption: 'messages',
                          onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SavedScreen())),
                        ),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: ActionTile(
                          icon: Icons.local_hospital_rounded,
                          label: 'Hospitals',
                          caption: 'nearby',
                          color: AppColors.red,
                          onTap: () => Launcher.nearbyHospitals(r.myLat, r.myLng),
                        ),
                      ),
                    ],
                  ),
                  if (r.mission != null) ...[
                    const SizedBox(height: 8),
                    OutlinedButton.icon(
                      onPressed: () => AppCoordinator.I.openChat(r.mission!.id, title: r.mission!.driverName),
                      icon: const Icon(Icons.chat_rounded),
                      label: const Text('Messages with the patient'),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _DutyCard extends StatelessWidget {
  const _DutyCard({
    required this.label,
    required this.hint,
    required this.icon,
    required this.color,
    required this.selected,
    required this.busy,
    required this.onTap,
  });
  final String label;
  final String hint;
  final IconData icon;
  final Color color;
  final bool selected;
  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => Expanded(
    child: AnimatedContainer(
      duration: const Duration(milliseconds: 200),
      decoration: BoxDecoration(
        color: selected ? color : Theme.of(context).cardTheme.color,
        borderRadius: BorderRadius.circular(18),
        border: Border.all(color: selected ? color : Colors.transparent, width: 2),
      ),
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: busy || selected ? null : onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(vertical: 16, horizontal: 8),
          child: Column(
            children: [
              Icon(icon, color: selected ? Colors.white : color, size: 30),
              const SizedBox(height: 6),
              Text(
                label,
                style: TextStyle(fontWeight: FontWeight.w900, color: selected ? Colors.white : null),
              ),
              Text(
                hint,
                textAlign: TextAlign.center,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 11, color: selected ? Colors.white70 : AppColors.muted),
              ),
            ],
          ),
        ),
      ),
    ),
  );
}

class _Stat extends StatelessWidget {
  const _Stat({required this.value, required this.label});
  final String value;
  final String label;

  @override
  Widget build(BuildContext context) => Panel(
    child: Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(value, style: const TextStyle(fontSize: 28, fontWeight: FontWeight.w900)),
        Text(label, style: const TextStyle(color: AppColors.muted)),
      ],
    ),
  );
}
