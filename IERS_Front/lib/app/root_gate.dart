import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../core/widgets/location_gate.dart';
import '../features/auth/login_screen.dart';
import '../features/driver/driver_home_screen.dart';
import '../features/responder/responder_home_screen.dart';
import '../services/auth_service.dart';
import 'coordinator.dart';

/// Shows the right home for who is logged in, and starts/stops the background parts.
class RootGate extends StatefulWidget {
  const RootGate({super.key});

  @override
  State<RootGate> createState() => _RootGateState();
}

class _RootGateState extends State<RootGate> with WidgetsBindingObserver {
  bool? _wasLoggedIn;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) AppCoordinator.I.resumed();
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthService>();
    final loggedIn = auth.isLoggedIn;
    if (_wasLoggedIn != loggedIn) {
      _wasLoggedIn = loggedIn;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (loggedIn) {
          AppCoordinator.I.loggedIn();
        } else {
          AppCoordinator.I.loggedOut();
          // Close any page left open from the old login.
          AppCoordinator.I.navKey.currentState?.popUntil((r) => r.isFirst);
        }
      });
    }
    if (!loggedIn) return const LoginScreen();
    return LocationGate(
      child: auth.isResponder ? const ResponderHomeScreen() : const DriverHomeScreen(),
    );
  }
}
