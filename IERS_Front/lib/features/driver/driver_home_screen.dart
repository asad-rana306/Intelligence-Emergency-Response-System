import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../app/coordinator.dart';
import '../../controllers/driver_controller.dart';
import '../../core/config/emergency_numbers.dart';
import '../../core/net/stomp_socket.dart';
import '../../core/theme/app_theme.dart';
import '../../core/widgets/common.dart';
import '../../core/widgets/hold_button.dart';
import '../../core/widgets/map_view.dart';
import '../../services/auth_service.dart';
import '../../services/crash_detector.dart';
import '../../services/launcher.dart';
import '../../services/live_service.dart';
import '../../services/location_service.dart';
import '../../services/profile_service.dart';
import '../common/saved_screen.dart';
import '../settings/settings_screen.dart';
import 'contacts_screen.dart';
import 'driver_history_screen.dart';
import 'medical_screen.dart';

class DriverHomeScreen extends StatefulWidget {
  const DriverHomeScreen({super.key});

  @override
  State<DriverHomeScreen> createState() => _DriverHomeScreenState();
}

class _DriverHomeScreenState extends State<DriverHomeScreen> {
  @override
  void initState() {
    super.initState();
    // The phone protects the driver as soon as the app is open (phone works as the crash box).
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!CrashDetector.I.running) CrashDetector.I.start();
    });
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthService>();
    final d = context.watch<DriverController>();
    final profile = context.watch<ProfileService>();

    return Scaffold(
      appBar: AppBar(
        title: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(auth.firstName.isEmpty ? 'Hello' : 'Hi, ${auth.firstName}'),
            const Text(
              'Stay safe on the road',
              style: TextStyle(fontSize: 13, color: AppColors.muted, fontWeight: FontWeight.w600),
            ),
          ],
        ),
        actions: [
          const _LiveDot(),
          IconButton(
            onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SettingsScreen())),
            icon: const Icon(Icons.settings_rounded),
          ),
        ],
      ),
      body: Column(
        children: [
          ValueListenableBuilder<SocketState>(
            valueListenable: LiveService.I.state,
            builder: (_, s, _) => ConnectionStrip(
              online: s == SocketState.online || s == SocketState.off,
              label: 'Reconnecting… SOS still works (it retries, and you can call 1122).',
            ),
          ),
          Expanded(
            child: RefreshIndicator(
              onRefresh: () async {
                await ProfileService.I.refresh();
                await DriverController.I.restore();
              },
              child: ListView(
                padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
                children: [
                  if (d.active)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Panel(
                        color: AppColors.red,
                        onTap: AppCoordinator.I.openEmergency,
                        child: const Row(
                          children: [
                            Icon(Icons.emergency_rounded, color: Colors.white),
                            SizedBox(width: 10),
                            Expanded(
                              child: Text(
                                'Emergency in progress — open',
                                style: TextStyle(color: Colors.white, fontWeight: FontWeight.w900, fontSize: 16),
                              ),
                            ),
                            Icon(Icons.chevron_right_rounded, color: Colors.white),
                          ],
                        ),
                      ),
                    ),
                  if (d.notice != null)
                    Padding(
                      padding: const EdgeInsets.only(bottom: 12),
                      child: Panel(
                        color: AppColors.blue.withValues(alpha: 0.1),
                        child: Row(
                          children: [
                            const Icon(Icons.info_rounded, color: AppColors.blue),
                            const SizedBox(width: 10),
                            Expanded(
                              child: Text(d.notice!, style: const TextStyle(fontWeight: FontWeight.w600)),
                            ),
                            IconButton(onPressed: d.clearNotice, icon: const Icon(Icons.close_rounded, size: 18)),
                          ],
                        ),
                      ),
                    ),

                  // Live driving dashboard: my speed + my location, always visible.
                  const _LiveDriveCard(),
                  const SizedBox(height: 20),

                  Center(child: HoldButton(onConfirmed: () => DriverController.I.sos())),
                  const SizedBox(height: 8),
                  const Text(
                    'Hold 2 seconds. Help comes to your location. You still get 10 s to cancel.',
                    textAlign: TextAlign.center,
                    style: TextStyle(color: AppColors.muted),
                  ),

                  const SectionTitle('Call now'),
                  Row(
                    children: [
                      for (final n in EmergencyNumbers.all) ...[
                        Expanded(
                          child: ActionTile(
                            icon: n == EmergencyNumbers.police ? Icons.local_police_rounded : Icons.emergency_rounded,
                            label: n.number,
                            caption: n.label,
                            color: AppColors.red,
                            onTap: () => Launcher.callNow(n.number),
                          ),
                        ),
                        if (n != EmergencyNumbers.all.last) const SizedBox(width: 8),
                      ],
                    ],
                  ),

                  SectionTitle(
                    'My emergency contacts',
                    trailing: TextButton(
                      onPressed: () =>
                          Navigator.push(context, MaterialPageRoute(builder: (_) => const ContactsScreen())),
                      child: const Text('Manage'),
                    ),
                  ),
                  if (profile.contacts.isEmpty)
                    Panel(
                      onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ContactsScreen())),
                      child: const Row(
                        children: [
                          Icon(Icons.person_add_alt_1_rounded, color: AppColors.red),
                          SizedBox(width: 12),
                          Expanded(
                            child: Text('Add family or friends. They get an SMS with your location after a crash.'),
                          ),
                        ],
                      ),
                    )
                  else
                    SizedBox(
                      height: 92,
                      child: ListView.separated(
                        scrollDirection: Axis.horizontal,
                        itemCount: profile.contacts.length,
                        separatorBuilder: (_, _) => const SizedBox(width: 8),
                        itemBuilder: (_, i) {
                          final c = profile.contacts[i];
                          return SizedBox(
                            width: 120,
                            child: ActionTile(
                              icon: Icons.call_rounded,
                              label: c.name,
                              caption: c.relationship ?? c.phone,
                              color: AppColors.green,
                              onTap: () => Launcher.callNow(c.phone),
                            ),
                          );
                        },
                      ),
                    ),

                  const SectionTitle('My safety'),
                  GridView.count(
                    crossAxisCount: 3,
                    shrinkWrap: true,
                    physics: const NeverScrollableScrollPhysics(),
                    mainAxisSpacing: 8,
                    crossAxisSpacing: 8,
                    childAspectRatio: 1.05,
                    children: [
                      ActionTile(
                        icon: Icons.medical_information_rounded,
                        label: 'Medical ID',
                        caption: profile.medical.bloodType ?? 'add info',
                        color: AppColors.red,
                        onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const MedicalScreen())),
                      ),
                      ActionTile(
                        icon: Icons.history_rounded,
                        label: 'History',
                        color: AppColors.ink,
                        onTap: () =>
                            Navigator.push(context, MaterialPageRoute(builder: (_) => const DriverHistoryScreen())),
                      ),
                      ActionTile(
                        icon: Icons.bookmark_rounded,
                        label: 'Saved',
                        caption: 'messages',
                        color: AppColors.ink,
                        onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const SavedScreen())),
                      ),
                      ActionTile(
                        icon: Icons.share_location_rounded,
                        label: 'Share location',
                        color: AppColors.green,
                        onTap: profile.contacts.isEmpty ? null : DriverController.I.smsContacts,
                      ),
                      ActionTile(
                        icon: Icons.local_hospital_rounded,
                        label: 'Hospitals',
                        caption: 'nearby',
                        color: AppColors.red,
                        onTap: () => Launcher.nearbyHospitals(null, null),
                      ),
                    ],
                  ),
                  const SizedBox(height: 16),
                  _ReadyNudge(profile: profile),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _LiveDot extends StatelessWidget {
  const _LiveDot();

  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<SocketState>(
      valueListenable: LiveService.I.state,
      builder: (_, s, _) {
        final online = s == SocketState.online;
        return Tooltip(
          message: online ? 'Connected' : 'Not connected',
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 6),
            child: Icon(Icons.circle, size: 12, color: online ? AppColors.green : AppColors.amber),
          ),
        );
      },
    );
  }
}

/// Live driving card: the driver's own speed and position on a map, updating as they drive.
/// Also shows that the phone is protecting them (crash sensor running).
class _LiveDriveCard extends StatefulWidget {
  const _LiveDriveCard();

  @override
  State<_LiveDriveCard> createState() => _LiveDriveCardState();
}

class _LiveDriveCardState extends State<_LiveDriveCard> {
  Timer? _poll;

  @override
  void initState() {
    super.initState();
    // Make sure we have at least one fix even before the sensor feed warms up.
    LocationService.I.current();
    // The crash detector streams GPS; we just read its latest value (no second stream).
    _poll = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _poll?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final detector = context.watch<CrashDetector>();
    final last = LocationService.I.last;
    final speed = detector.speedKmh > 0 ? detector.speedKmh : (last != null ? LocationService.kmh(last) : 0);
    return Panel(
      padding: EdgeInsets.zero,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          SizedBox(
            height: 150,
            child: Stack(
              children: [
                Positioned.fill(
                  child: MapView(
                    pins: last != null
                        ? [MapPin(last.latitude, last.longitude, icon: Icons.navigation_rounded, color: AppColors.blue, label: 'You')]
                        : const [],
                    line: false,
                    borderRadius: 0,
                    interactive: false,
                  ),
                ),
                Positioned(
                  left: 12,
                  top: 12,
                  child: Container(
                    padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(14),
                      boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 8)],
                    ),
                    child: Row(
                      mainAxisSize: MainAxisSize.min,
                      crossAxisAlignment: CrossAxisAlignment.end,
                      children: [
                        Text(
                          '${speed.round()}',
                          style: const TextStyle(fontSize: 34, fontWeight: FontWeight.w900, height: 1, color: AppColors.ink),
                        ),
                        const SizedBox(width: 4),
                        const Padding(
                          padding: EdgeInsets.only(bottom: 5),
                          child: Text('km/h', style: TextStyle(fontWeight: FontWeight.w700, color: AppColors.muted)),
                        ),
                      ],
                    ),
                  ),
                ),
              ],
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(14),
            child: Row(
              children: [
                Icon(
                  detector.running ? Icons.shield_rounded : Icons.shield_outlined,
                  color: detector.running ? AppColors.green : AppColors.amber,
                ),
                const SizedBox(width: 10),
                Expanded(
                  child: Text(
                    detector.running
                        ? 'Protected — this phone is watching for a crash'
                        : 'Protection is off',
                    style: const TextStyle(fontWeight: FontWeight.w700),
                  ),
                ),
                Switch(
                  value: detector.running,
                  activeThumbColor: AppColors.green,
                  onChanged: (on) => on ? CrashDetector.I.start() : CrashDetector.I.stop(),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Gentle nudge to finish set-up (medical ID + a contact). No permission checks here —
/// location is handled by the gate, and mic/phone are asked for when first needed.
class _ReadyNudge extends StatelessWidget {
  const _ReadyNudge({required this.profile});
  final ProfileService profile;

  @override
  Widget build(BuildContext context) {
    final items = <(String, bool, VoidCallback)>[
      (
        'Medical ID filled in',
        !profile.medical.isEmpty,
        () => Navigator.push(context, MaterialPageRoute(builder: (_) => const MedicalScreen())),
      ),
      (
        'At least one emergency contact',
        profile.contacts.isNotEmpty,
        () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ContactsScreen())),
      ),
    ];
    final left = items.where((i) => !i.$2).length;
    if (left == 0) return const SizedBox.shrink();
    return Panel(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Get ready ($left left)', style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 6),
          for (final i in items)
            ListTile(
              contentPadding: EdgeInsets.zero,
              dense: true,
              leading: Icon(
                i.$2 ? Icons.check_circle_rounded : Icons.radio_button_unchecked_rounded,
                color: i.$2 ? AppColors.green : AppColors.muted,
              ),
              title: Text(
                i.$1,
                style: TextStyle(fontWeight: FontWeight.w600, decoration: i.$2 ? TextDecoration.lineThrough : null),
              ),
              trailing: i.$2 ? null : const Icon(Icons.chevron_right_rounded),
              onTap: i.$2 ? null : i.$3,
            ),
        ],
      ),
    );
  }
}
