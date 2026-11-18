import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';

import '../theme/app_theme.dart';

/// The app needs location to send help to the right place.
/// This wraps the home screens: until location is allowed, it shows a clear
/// "Turn on location" screen with a button. Works on phone and on Chrome
/// (uses Geolocator, which has a real web implementation — unlike permission_handler).
class LocationGate extends StatefulWidget {
  const LocationGate({super.key, required this.child});
  final Widget child;

  @override
  State<LocationGate> createState() => _LocationGateState();
}

class _LocationGateState extends State<LocationGate> with WidgetsBindingObserver {
  bool _checking = true;
  bool _granted = false;
  bool _serviceOff = false;
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _check();
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Coming back from the OS settings page — re-check.
    if (state == AppLifecycleState.resumed) _check();
  }

  Future<void> _check() async {
    try {
      final service = await Geolocator.isLocationServiceEnabled();
      final perm = await Geolocator.checkPermission();
      final ok = perm == LocationPermission.always || perm == LocationPermission.whileInUse;
      if (!mounted) return;
      setState(() {
        _serviceOff = !service;
        _granted = ok && service;
        _checking = false;
      });
    } catch (_) {
      if (!mounted) return;
      // If the check itself fails, don't lock the user out — let them in.
      setState(() {
        _granted = true;
        _checking = false;
      });
    }
  }

  Future<void> _enable() async {
    setState(() => _busy = true);
    try {
      if (!await Geolocator.isLocationServiceEnabled()) {
        await Geolocator.openLocationSettings();
      }
      var perm = await Geolocator.checkPermission();
      if (perm == LocationPermission.denied) {
        perm = await Geolocator.requestPermission();
      }
      if (perm == LocationPermission.deniedForever) {
        await Geolocator.openAppSettings();
      }
    } catch (_) {
      // ignore — _check() below reports the real state
    }
    await _check();
    if (mounted) setState(() => _busy = false);
  }

  @override
  Widget build(BuildContext context) {
    if (_checking) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (_granted) return widget.child;

    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(28),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Container(
                width: 96,
                height: 96,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.red.withValues(alpha: 0.1),
                  shape: BoxShape.circle,
                ),
                child: const Icon(Icons.location_on_rounded, color: AppColors.red, size: 52),
              ),
              const SizedBox(height: 28),
              Text(
                'Turn on location',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineMedium,
              ),
              const SizedBox(height: 12),
              Text(
                _serviceOff
                    ? 'Location is switched off on this device. IERS needs it to send help to exactly where you are.'
                    : 'IERS needs your location so rescue can reach exactly where you are. Please allow it to continue.',
                textAlign: TextAlign.center,
                style: const TextStyle(color: AppColors.muted, fontSize: 16, height: 1.4),
              ),
              const SizedBox(height: 32),
              FilledButton.icon(
                onPressed: _busy ? null : _enable,
                icon: _busy
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2.5, color: Colors.white),
                      )
                    : const Icon(Icons.my_location_rounded),
                label: Text(_busy ? 'Checking…' : 'Enable location'),
              ),
              const SizedBox(height: 12),
              TextButton(
                onPressed: _busy ? null : _check,
                child: const Text('I already allowed it — retry'),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
