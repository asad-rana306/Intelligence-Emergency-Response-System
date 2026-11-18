import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../../app/coordinator.dart';
import '../../controllers/driver_controller.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/fmt.dart';
import '../../core/utils/geo.dart';
import '../../core/widgets/common.dart';
import '../../core/widgets/map_view.dart';
import '../../models/models.dart';
import '../../services/chat_service.dart';
import '../../services/launcher.dart';
import '../../services/profile_service.dart';

/// The driver's emergency, full screen:
///   countdown  → giant red alarm + one huge "I'M OK" button (10 s)
///   afterwards → status, map, the unit's contact buttons, the bystander panel, and a SMALL cancel
///   finished   → "you are safe"
class EmergencyFlowScreen extends StatefulWidget {
  const EmergencyFlowScreen({super.key});

  @override
  State<EmergencyFlowScreen> createState() => _EmergencyFlowScreenState();
}

class _EmergencyFlowScreenState extends State<EmergencyFlowScreen> {
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

  @override
  Widget build(BuildContext context) {
    final d = context.watch<DriverController>();
    if (d.phase == DriverPhase.idle) {
      if (!_closing) {
        _closing = true;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) closeOwnPage(context);
        });
      }
      return const Scaffold(body: SizedBox());
    }
    return PopScope(
      canPop: d.phase != DriverPhase.countdown,
      child: Listener(
        onPointerDown: (_) {
          if (d.phase != DriverPhase.countdown) d.touched();
        },
        child: switch (d.phase) {
          DriverPhase.countdown => _Countdown(d: d),
          DriverPhase.finished => _Finished(d: d),
          _ => _HelpComing(d: d),
        },
      ),
    );
  }
}

// ─────────────────────────────── 1. countdown ───────────────────────────────

class _Countdown extends StatelessWidget {
  const _Countdown({required this.d});
  final DriverController d;

  @override
  Widget build(BuildContext context) {
    final secs = (d.remaining.inMilliseconds / 1000).ceil();
    final frac = (d.remaining.inMilliseconds / 10000).clamp(0.0, 1.0);
    return Scaffold(
      backgroundColor: AppColors.red,
      body: TweenAnimationBuilder<double>(
        tween: Tween(begin: 0.85, end: 1),
        duration: const Duration(milliseconds: 600),
        builder: (context, v, child) => Container(
          decoration: BoxDecoration(
            gradient: RadialGradient(
              colors: [Color.lerp(AppColors.red, Colors.white, 0.15 * v)!, AppColors.redDark],
              radius: 1.2 * v,
            ),
          ),
          child: child,
        ),
        child: SafeArea(
          child: Padding(
            padding: const EdgeInsets.fromLTRB(24, 16, 24, 24),
            child: Column(
              children: [
                const SizedBox(height: 8),
                const Icon(Icons.warning_rounded, color: Colors.white, size: 54),
                const SizedBox(height: 8),
                Text(
                  d.manualSos ? 'SOS' : 'CRASH DETECTED',
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    color: Colors.white,
                    fontSize: 34,
                    fontWeight: FontWeight.w900,
                    letterSpacing: 1,
                  ),
                ),
                const SizedBox(height: 6),
                Text(
                  d.manualSos ? 'Help will be sent to your location' : 'Help will be sent automatically',
                  style: const TextStyle(color: Colors.white70, fontSize: 17, fontWeight: FontWeight.w600),
                ),
                const Spacer(),
                SizedBox(
                  width: 210,
                  height: 210,
                  child: Stack(
                    alignment: Alignment.center,
                    children: [
                      SizedBox(
                        width: 210,
                        height: 210,
                        child: CircularProgressIndicator(
                          value: frac,
                          strokeWidth: 12,
                          color: Colors.white,
                          backgroundColor: Colors.white24,
                        ),
                      ),
                      Column(
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            '$secs',
                            style: const TextStyle(
                              color: Colors.white,
                              fontSize: 96,
                              fontWeight: FontWeight.w900,
                              height: 1,
                            ),
                          ),
                          const Text(
                            'seconds',
                            style: TextStyle(color: Colors.white70, fontWeight: FontWeight.w700),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
                const Spacer(),
                if (d.sendProblem != null)
                  Padding(
                    padding: const EdgeInsets.only(bottom: 12),
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.center,
                      children: [
                        const Icon(Icons.wifi_off_rounded, color: Colors.white, size: 18),
                        const SizedBox(width: 6),
                        Flexible(
                          child: Text(
                            d.sendProblem!,
                            style: const TextStyle(color: Colors.white, fontWeight: FontWeight.w600),
                          ),
                        ),
                      ],
                    ),
                  ),
                SizedBox(
                  width: double.infinity,
                  height: 92,
                  child: FilledButton(
                    style: FilledButton.styleFrom(
                      backgroundColor: Colors.white,
                      foregroundColor: AppColors.redDark,
                      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(26)),
                    ),
                    onPressed: d.cancelBusy ? null : d.cancel,
                    child: d.cancelBusy
                        ? const CircularProgressIndicator()
                        : const Column(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text(
                                "I'M OK",
                                style: TextStyle(fontSize: 30, fontWeight: FontWeight.w900, letterSpacing: 1),
                              ),
                              Text('Tap to cancel', style: TextStyle(fontSize: 14, fontWeight: FontWeight.w700)),
                            ],
                          ),
                  ),
                ),
                const SizedBox(height: 12),
                Text(
                  d.source == 'BOX' ? 'Detected by your car box' : 'Detected by this phone',
                  style: const TextStyle(color: Colors.white60, fontSize: 13),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

// ─────────────────────────────── 2. help is coming ───────────────────────────────

class _HelpComing extends StatelessWidget {
  const _HelpComing({required this.d});
  final DriverController d;

  @override
  Widget build(BuildContext context) {
    final medical = context.watch<ProfileService>().medical;
    final unread = d.incidentId == null ? 0 : context.watch<ChatService>().unreadFor(d.incidentId!);
    final accepted = d.responder != null && !['DISPATCHING', 'DISPATCHED'].contains(d.status);

    return Scaffold(
      appBar: AppBar(
        backgroundColor: Colors.transparent,
        automaticallyImplyLeading: false,
        title: const Text('Emergency'),
        actions: [
          IconButton(
            tooltip: d.announcing ? 'Stop the loud announcement' : 'Announce loudly for people nearby',
            onPressed: d.toggleAnnouncement,
            icon: Icon(
              d.announcing ? Icons.volume_off_rounded : Icons.campaign_rounded,
              color: d.announcing ? AppColors.red : null,
            ),
          ),
          IconButton(
            tooltip: 'Minimise',
            onPressed: () => Navigator.maybePop(context),
            icon: const Icon(Icons.keyboard_arrow_down_rounded),
          ),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
        children: [
          _StatusHeader(d: d),
          const SizedBox(height: 12),

          if (d.phase == DriverPhase.offline) _OfflineCard(d: d),
          if (d.noResponder) _NoUnitCard(d: d),
          if (d.phase == DriverPhase.requesting &&
              d.requestingSince != null &&
              DateTime.now().difference(d.requestingSince!) > const Duration(seconds: 40))
            _SlowCard(d: d),

          // Announcement control — always visible during an emergency so it is easy to find.
          Padding(
            padding: const EdgeInsets.only(bottom: 12),
            child: d.announceDisabledForever
                ? Panel(
                    color: const Color(0xFFEFF1F4),
                    child: Row(
                      children: [
                        const Icon(Icons.volume_off_rounded, color: AppColors.muted),
                        const SizedBox(width: 10),
                        const Expanded(
                          child: Text('Announcement is off for this accident.',
                              style: TextStyle(fontWeight: FontWeight.w700, color: AppColors.muted)),
                        ),
                        TextButton(onPressed: d.toggleAnnouncement, child: const Text('Turn on')),
                      ],
                    ),
                  )
                : Panel(
                    color: d.announcing ? AppColors.red.withValues(alpha: 0.12) : AppColors.amber.withValues(alpha: 0.16),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Row(
                          children: [
                            Icon(Icons.campaign_rounded, color: d.announcing ? AppColors.red : AppColors.amber),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                d.announcing
                                    ? 'Speaking loudly to call people nearby for help.'
                                    : 'If nobody is around, the phone can call out loudly for help.',
                                style: const TextStyle(fontWeight: FontWeight.w700),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 10),
                        SizedBox(
                          width: double.infinity,
                          child: FilledButton.icon(
                            style: FilledButton.styleFrom(
                              backgroundColor: d.announcing ? AppColors.red : AppColors.ink,
                            ),
                            onPressed: d.stopAnnouncementForever,
                            icon: const Icon(Icons.volume_off_rounded),
                            label: Text(d.announcing ? 'STOP the voice now' : 'Stop announcement for this accident'),
                          ),
                        ),
                      ],
                    ),
                  ),
          ),

          // Map: me (crash) + the unit
          if (d.crashLat != null && d.crashLng != null)
            SizedBox(
              height: 220,
              child: MapView(
                borderRadius: 20,
                pins: [
                  MapPin(d.crashLat!, d.crashLng!, label: 'You'),
                  if (d.unitLat != null && d.unitLng != null)
                    MapPin(
                      d.unitLat!,
                      d.unitLng!,
                      icon: Icons.local_hospital_rounded,
                      color: AppColors.blue,
                      label: 'Unit',
                    ),
                ],
              ),
            ),
          if (d.unitLat != null && d.crashLat != null) ...[const SizedBox(height: 8), _EtaLine(d: d)],
          const SizedBox(height: 12),

          // The unit
          if (accepted) _UnitCard(d: d, unread: unread),
          if (!accepted && d.phase == DriverPhase.incident)
            const Panel(
              child: Row(
                children: [
                  SizedBox(width: 26, height: 26, child: CircularProgressIndicator(strokeWidth: 3)),
                  SizedBox(width: 14),
                  Expanded(
                    child: Text(
                      'Finding the nearest rescue unit…',
                      style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                    ),
                  ),
                ],
              ),
            ),
          const SizedBox(height: 12),

          _BystanderPanel(d: d, medical: medical.isEmpty ? null : medical),
          const SizedBox(height: 12),

          Panel(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Family & friends', style: TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
                const SizedBox(height: 4),
                Text(
                  ProfileService.I.contacts.isEmpty
                      ? 'You have no emergency contacts yet.'
                      : 'Your ${ProfileService.I.contacts.length} emergency contact(s) get an SMS from IERS.',
                  style: const TextStyle(color: AppColors.muted),
                ),
                if (ProfileService.I.contacts.isNotEmpty) ...[
                  const SizedBox(height: 10),
                  OutlinedButton.icon(
                    onPressed: d.smsContacts,
                    icon: const Icon(Icons.sms_outlined),
                    label: const Text('Send my location by SMS too'),
                  ),
                ],
              ],
            ),
          ),
          const SizedBox(height: 28),

          // Small, but always visible
          Center(
            child: TextButton.icon(
              onPressed: d.cancelBusy
                  ? null
                  : () async {
                      final ok = await confirm(
                        context,
                        title: 'Cancel help?',
                        message: 'Only if nobody is hurt. The rescue unit will be told to stand down.',
                        yes: 'Yes, I am OK',
                        no: 'Keep help coming',
                      );
                      if (ok) d.lateCancel();
                    },
              icon: const Icon(Icons.close_rounded, size: 18, color: AppColors.muted),
              label: const Text(
                "I'm OK — cancel help",
                style: TextStyle(color: AppColors.muted, fontWeight: FontWeight.w700),
              ),
            ),
          ),
          if (d.sendProblem != null)
            Center(
              child: Text(d.sendProblem!, style: const TextStyle(color: AppColors.red, fontSize: 12)),
            ),
        ],
      ),
    );
  }
}

class _StatusHeader extends StatelessWidget {
  const _StatusHeader({required this.d});
  final DriverController d;

  int get _step {
    if (d.phase == DriverPhase.requesting || d.phase == DriverPhase.offline) return 0;
    switch (d.status) {
      case 'DISPATCHING':
      case 'DISPATCHED':
        return 1;
      case 'ACCEPTED':
        return 2;
      case 'ARRIVED':
        return 3;
      case 'EN_ROUTE_HOSPITAL':
        return 4;
      default:
        return 1;
    }
  }

  String get _title {
    if (d.phase == DriverPhase.offline) return 'No internet';
    if (d.phase == DriverPhase.requesting) return 'Requesting help…';
    switch (d.status) {
      case 'ACCEPTED':
        return 'Help is on the way';
      case 'ARRIVED':
        return 'The unit has arrived';
      case 'EN_ROUTE_HOSPITAL':
        return 'Going to hospital';
      default:
        return 'Finding a rescue unit';
    }
  }

  @override
  Widget build(BuildContext context) {
    const labels = ['Sent', 'Finding', 'Coming', 'Arrived', 'Hospital'];
    final color = d.phase == DriverPhase.offline ? AppColors.amber : (_step >= 2 ? AppColors.green : AppColors.red);
    return Panel(
      color: color,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            _title,
            style: const TextStyle(color: Colors.white, fontSize: 24, fontWeight: FontWeight.w900),
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              for (var i = 0; i < labels.length; i++) ...[
                Expanded(
                  child: Column(
                    children: [
                      Container(
                        height: 6,
                        decoration: BoxDecoration(
                          color: i <= _step ? Colors.white : Colors.white30,
                          borderRadius: BorderRadius.circular(4),
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        labels[i],
                        style: TextStyle(
                          color: i <= _step ? Colors.white : Colors.white60,
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                        ),
                      ),
                    ],
                  ),
                ),
                if (i < labels.length - 1) const SizedBox(width: 4),
              ],
            ],
          ),
        ],
      ),
    );
  }
}

class _EtaLine extends StatelessWidget {
  const _EtaLine({required this.d});
  final DriverController d;

  @override
  Widget build(BuildContext context) {
    final m = Geo.meters(d.unitLat!, d.unitLng!, d.crashLat!, d.crashLng!);
    final eta = d.etaMinutes != null ? '~${d.etaMinutes!.ceil()} min' : Geo.etaText(Geo.roughEta(m));
    return Row(
      children: [
        const Icon(Icons.local_hospital_rounded, color: AppColors.blue, size: 20),
        const SizedBox(width: 6),
        Text('Unit is ${Geo.distanceText(m)} away · $eta', style: const TextStyle(fontWeight: FontWeight.w800)),
      ],
    );
  }
}

class _UnitCard extends StatelessWidget {
  const _UnitCard({required this.d, required this.unread});
  final DriverController d;
  final int unread;

  @override
  Widget build(BuildContext context) {
    final r = d.responder!;
    return Panel(
      child: Column(
        children: [
          Row(
            children: [
              const Avatar(null, icon: Icons.local_hospital_rounded, color: AppColors.green, size: 52),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(r.name ?? 'Rescue unit', style: Theme.of(context).textTheme.titleLarge),
                    Text(
                      [r.vehicleId, Fmt.status(d.status)].whereType<String>().join(' · '),
                      style: const TextStyle(color: AppColors.muted, fontWeight: FontWeight.w600),
                    ),
                  ],
                ),
              ),
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
                  onTap: () => d.callResponder(),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: ActionTile(
                  icon: Icons.phone_in_talk_rounded,
                  label: 'Phone',
                  caption: 'no internet',
                  color: AppColors.blue,
                  onTap: (r.phone ?? '').isEmpty ? null : () => Launcher.callNow(r.phone!),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: ActionTile(
                  icon: Icons.chat_rounded,
                  label: 'Message',
                  caption: 'text / voice',
                  badge: unread,
                  color: AppColors.ink,
                  onTap: () => AppCoordinator.I.openChat(d.incidentId!, title: r.name),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: ActionTile(
                  icon: Icons.map_rounded,
                  label: 'Map',
                  caption: 'where is it',
                  color: AppColors.ink,
                  onTap: d.unitLat == null
                      ? (d.crashLat == null ? null : () => Launcher.nearbyHospitals(d.crashLat, d.crashLng))
                      : () => Launcher.showOnMap(d.unitLat!, d.unitLng!, label: 'Rescue unit'),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _BystanderPanel extends StatelessWidget {
  const _BystanderPanel({required this.d, required this.medical});
  final DriverController d;
  final MedicalProfile? medical;

  @override
  Widget build(BuildContext context) {
    final m = medical;
    String? v(String? s) => (s == null || s.trim().isEmpty) ? null : s.trim();
    return Panel(
      color: const Color(0xFFFFF7E6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Row(
            children: [
              Icon(Icons.front_hand_rounded, color: Color(0xFFB45309)),
              SizedBox(width: 8),
              Expanded(
                child: Text(
                  'Are you helping the driver?',
                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900, color: Color(0xFF7C2D12)),
                ),
              ),
            ],
          ),
          const SizedBox(height: 10),
          FilledButton.icon(
            style: FilledButton.styleFrom(backgroundColor: AppColors.green, minimumSize: const Size.fromHeight(64)),
            onPressed: d.bystanderHelping,
            icon: const Icon(Icons.record_voice_over_rounded),
            label: Text(
              d.bystanderMarked ? 'Talk to the rescue team' : "I'm helping — talk to rescue",
              style: const TextStyle(fontSize: 18),
            ),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: d.callRescue,
            icon: const Icon(Icons.emergency_rounded, color: AppColors.red),
            label: const Text('Call 1122 Rescue', style: TextStyle(color: AppColors.red)),
          ),
          if (m != null) ...[
            const SizedBox(height: 14),
            const Text(
              'MEDICAL ID',
              style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1, color: Color(0xFF7C2D12)),
            ),
            const SizedBox(height: 6),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              children: [
                if (v(m.bloodType) != null)
                  Pill('Blood ${m.bloodType}', color: AppColors.red, icon: Icons.bloodtype_rounded),
                if (v(m.allergies) != null) Pill('Allergic: ${m.allergies}', color: const Color(0xFFB45309)),
                if (v(m.medications) != null) Pill('Takes: ${m.medications}', color: AppColors.blue),
                if (v(m.chronicConditions) != null) Pill(m.chronicConditions!, color: AppColors.ink),
              ],
            ),
            if (v(m.emergencyNotes) != null) ...[
              const SizedBox(height: 6),
              Text(m.emergencyNotes!, style: const TextStyle(fontWeight: FontWeight.w600)),
            ],
          ],
          const SizedBox(height: 14),
          const Text(
            'UNTIL HELP ARRIVES',
            style: TextStyle(fontWeight: FontWeight.w900, letterSpacing: 1, color: Color(0xFF7C2D12)),
          ),
          const SizedBox(height: 6),
          for (final tip in const [
            'Do not move the driver unless there is fire or other danger.',
            'Turn off the engine and switch on the hazard lights.',
            'Keep people away from traffic. Stay on the phone with the rescue team.',
            'Press firmly on heavy bleeding with a clean cloth.',
          ])
            Padding(
              padding: const EdgeInsets.only(bottom: 4),
              child: Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('•  ', style: TextStyle(fontWeight: FontWeight.w900)),
                  Expanded(child: Text(tip)),
                ],
              ),
            ),
        ],
      ),
    );
  }
}

class _OfflineCard extends StatelessWidget {
  const _OfflineCard({required this.d});
  final DriverController d;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Panel(
      color: AppColors.red.withValues(alpha: 0.08),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'Help could not be requested yet',
            style: TextStyle(fontSize: 17, fontWeight: FontWeight.w900, color: AppColors.red),
          ),
          const SizedBox(height: 4),
          const Text('The phone keeps trying. A normal call and SMS work without internet.'),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: d.callRescue,
            icon: const Icon(Icons.call_rounded),
            label: const Text('Call 1122 now'),
          ),
          const SizedBox(height: 8),
          OutlinedButton.icon(
            onPressed: d.smsContacts,
            icon: const Icon(Icons.sms_rounded),
            label: const Text('SMS my location to family'),
          ),
        ],
      ),
    ),
  );
}

class _NoUnitCard extends StatelessWidget {
  const _NoUnitCard({required this.d});
  final DriverController d;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Panel(
      color: AppColors.red.withValues(alpha: 0.08),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Text(
            'No unit has accepted yet',
            style: TextStyle(fontSize: 17, fontWeight: FontWeight.w900, color: AppColors.red),
          ),
          const SizedBox(height: 4),
          const Text('IERS keeps searching. Call 1122 as well.'),
          const SizedBox(height: 12),
          FilledButton.icon(
            onPressed: d.callRescue,
            icon: const Icon(Icons.call_rounded),
            label: const Text('Call 1122 now'),
          ),
        ],
      ),
    ),
  );
}

class _SlowCard extends StatelessWidget {
  const _SlowCard({required this.d});
  final DriverController d;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.only(bottom: 12),
    child: Panel(
      color: AppColors.amber.withValues(alpha: 0.15),
      child: Row(
        children: [
          const Expanded(
            child: Text('This is taking longer than usual.', style: TextStyle(fontWeight: FontWeight.w700)),
          ),
          TextButton(onPressed: d.callRescue, child: const Text('Call 1122')),
        ],
      ),
    ),
  );
}

// ─────────────────────────────── 3. finished ───────────────────────────────

class _Finished extends StatelessWidget {
  const _Finished({required this.d});
  final DriverController d;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            children: [
              const Spacer(),
              const Icon(Icons.verified_rounded, color: AppColors.green, size: 96),
              const SizedBox(height: 16),
              Text(
                'The emergency is complete',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const SizedBox(height: 8),
              const Text(
                'Take care. Your history keeps a record of it.',
                textAlign: TextAlign.center,
                style: TextStyle(color: AppColors.muted, fontSize: 16),
              ),
              const Spacer(),
              FilledButton(
                style: FilledButton.styleFrom(backgroundColor: AppColors.green),
                onPressed: () {
                  d.done();
                },
                child: const Text('Done'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
