import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../controllers/responder_controller.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/geo.dart';
import '../../core/widgets/common.dart';
import '../../core/widgets/map_view.dart';
import '../../models/models.dart';
import 'mission_screen.dart';

/// A new emergency, shown like a ride request: map on top, the facts below,
/// two big buttons. No timer on screen.
class OfferScreen extends StatefulWidget {
  const OfferScreen({super.key});

  @override
  State<OfferScreen> createState() => _OfferScreenState();
}

class _OfferScreenState extends State<OfferScreen> {
  bool _closing = false;
  Offer? _shown;

  Future<void> _accept() async {
    final r = ResponderController.I;
    final err = await r.accept();
    if (!mounted) return;
    if (err != null) {
      toast(context, err, error: true);
      if (r.offer == null) closeOwnPage(context);
      return;
    }
    Navigator.pushReplacement(context, MaterialPageRoute(builder: (_) => const MissionScreen()));
  }

  Future<void> _reject() async {
    final ok = await confirm(
      context,
      title: 'Reject this emergency?',
      message: 'It goes to the next nearest unit. You can still take it from History while nobody has accepted it.',
      yes: 'Reject',
      no: 'Go back',
    );
    if (!ok || !mounted) return;
    final err = await ResponderController.I.reject();
    if (!mounted) return;
    if (err != null) toast(context, err, error: true);
    closeOwnPage(context);
  }

  @override
  Widget build(BuildContext context) {
    final r = context.watch<ResponderController>();
    final o = r.offer ?? _shown;
    if (r.offer == null) {
      // Expired or taken by another unit while the page was open (an accept replaces this page itself).
      if (!_closing && !r.actionBusy) {
        _closing = true;
        WidgetsBinding.instance.addPostFrameCallback((_) {
          if (mounted) closeOwnPage(context);
        });
      }
    } else {
      _shown = r.offer;
    }
    if (o == null) return const Scaffold(body: SizedBox());

    final level = Priority.level(o.priorityScore);
    final pColor = AppColors.priority(level);
    final dist = r.distanceToCrash;

    return PopScope(
      canPop: true,
      child: Scaffold(
        body: Stack(
          children: [
            Positioned.fill(
              bottom: MediaQuery.of(context).size.height * 0.42,
              child: MapView(
                pins: [
                  if (o.lat != null && o.lng != null)
                    MapPin(o.lat!, o.lng!, icon: Icons.car_crash_rounded, label: 'Crash'),
                  if (r.myLat != null && r.myLng != null)
                    MapPin(r.myLat!, r.myLng!, icon: Icons.local_shipping_rounded, color: AppColors.blue, label: 'You'),
                ],
              ),
            ),
            SafeArea(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Row(
                  children: [
                    Pill('NEW EMERGENCY', color: AppColors.red, filled: true, icon: Icons.notifications_active_rounded),
                    const Spacer(),
                    Material(
                      color: Colors.white,
                      shape: const CircleBorder(),
                      child: IconButton(
                        tooltip: 'Close (the request stays open for a short time)',
                        onPressed: () => Navigator.pop(context),
                        icon: const Icon(Icons.keyboard_arrow_down_rounded),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            Align(
              alignment: Alignment.bottomCenter,
              child: Container(
                width: double.infinity,
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 20),
                decoration: BoxDecoration(
                  color: Theme.of(context).cardTheme.color,
                  borderRadius: const BorderRadius.vertical(top: Radius.circular(28)),
                  boxShadow: const [BoxShadow(color: Colors.black26, blurRadius: 20)],
                ),
                child: SafeArea(
                  top: false,
                  child: Column(
                    mainAxisSize: MainAxisSize.min,
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Row(
                        children: [
                          Pill(Priority.label(o.priorityScore), color: pColor, filled: true),
                          const SizedBox(width: 8),
                          if (o.attempt > 1) Pill('Asked ${o.attempt}x', color: AppColors.muted),
                          const Spacer(),
                          if (dist != null)
                            Text(
                              '${Geo.distanceText(dist)} · ${Geo.etaText(Geo.roughEta(dist))}',
                              style: const TextStyle(fontWeight: FontWeight.w900, fontSize: 16),
                            ),
                        ],
                      ),
                      const SizedBox(height: 12),
                      Text('Car crash', style: Theme.of(context).textTheme.headlineMedium),
                      const SizedBox(height: 2),
                      Text(
                        o.driverName ?? 'Unknown driver',
                        style: const TextStyle(fontSize: 17, color: AppColors.muted, fontWeight: FontWeight.w700),
                      ),
                      const SizedBox(height: 12),
                      Wrap(
                        spacing: 8,
                        runSpacing: 8,
                        children: [
                          if ((o.bloodType ?? '').isNotEmpty && o.bloodType != 'UNKNOWN')
                            Pill('Blood ${o.bloodType}', color: AppColors.red, icon: Icons.bloodtype_rounded),
                          if ((o.allergies ?? '').isNotEmpty && o.allergies != 'UNKNOWN')
                            Pill(
                              'Allergy: ${o.allergies}',
                              color: const Color(0xFFB45309),
                              icon: Icons.warning_amber_rounded,
                            ),
                          const Pill('You are the nearest unit', color: AppColors.blue, icon: Icons.near_me_rounded),
                        ],
                      ),
                      const SizedBox(height: 20),
                      Row(
                        children: [
                          Expanded(
                            child: SizedBox(
                              height: 64,
                              child: OutlinedButton(
                                style: OutlinedButton.styleFrom(
                                  foregroundColor: AppColors.red,
                                  side: const BorderSide(color: AppColors.red, width: 2),
                                ),
                                onPressed: r.actionBusy ? null : _reject,
                                child: const Text(
                                  'Reject',
                                  style: TextStyle(fontSize: 18, fontWeight: FontWeight.w900),
                                ),
                              ),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            flex: 2,
                            child: SizedBox(
                              height: 64,
                              child: FilledButton(
                                style: FilledButton.styleFrom(backgroundColor: AppColors.green),
                                onPressed: r.actionBusy ? null : _accept,
                                child: r.actionBusy
                                    ? const CircularProgressIndicator(color: Colors.white)
                                    : const Text(
                                        'ACCEPT',
                                        style: TextStyle(fontSize: 22, fontWeight: FontWeight.w900, letterSpacing: 1),
                                      ),
                              ),
                            ),
                          ),
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
