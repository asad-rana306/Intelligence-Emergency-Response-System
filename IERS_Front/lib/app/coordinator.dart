import 'dart:async';

import 'package:flutter/material.dart';

import '../controllers/driver_controller.dart';
import '../controllers/responder_controller.dart';
import '../core/net/session.dart';
import '../core/theme/app_theme.dart';
import '../features/call/call_screen.dart';
import '../features/chat/chat_screen.dart';
import '../features/driver/emergency_flow_screen.dart';
import '../features/responder/offer_screen.dart';
import '../models/models.dart';
import '../services/alarm_service.dart';
import '../services/background_service.dart';
import '../services/call_service.dart';
import '../services/chat_service.dart';
import '../services/crash_detector.dart';
import '../services/duty_service.dart';
import '../services/gps_service.dart';
import '../services/live_service.dart';
import '../services/local_alerts.dart';
import '../services/profile_service.dart';
import '../services/push_service.dart';

/// Starts/stops the background parts after login/logout, and opens the full-screen pages
/// that can appear at any moment: the crash alarm, a new request, an incoming call.
class AppCoordinator {
  AppCoordinator._();
  static final AppCoordinator I = AppCoordinator._();

  final navKey = GlobalKey<NavigatorState>();
  final messengerKey = GlobalKey<ScaffoldMessengerState>();

  bool _running = false;
  bool _flowOpen = false;
  bool _offerOpen = false;
  bool _callOpen = false;
  bool _detectorServiceOn = false;
  StreamSubscription? _chatSub;

  Future<void> loggedIn() async {
    if (_running) return;
    _running = true;
    // Push: this phone's token goes to Auth, so rescue requests and "help is on the way" arrive
    // even when the app is closed. Notification taps and buttons come back through LocalAlerts.
    PushService.I.registerAfterLogin();
    LocalAlerts.I.onTap = (_) => resumed();
    LocalAlerts.I.onActionDone = _onNotificationAction;
    await LiveService.I.start();
    CallService.I.start();
    ChatService.I.start();
    CallService.I.addListener(_onCall);
    _chatSub = ChatService.I.incoming.listen(_onIncomingChat);
    if (Session.I.isResponder) {
      ResponderController.I.start();
      ResponderController.I.incomingOffer.addListener(_onOffer);
    } else {
      DriverController.I.start();
      DriverController.I.addListener(_onDriver);
      // While the phone watches for crashes, the background service keeps the sensors on
      // when the screen is off or the app is closed.
      CrashDetector.I.addListener(_onDetector);
      _onDetector();
      ProfileService.I.refresh();
      ProfileService.I.deviceStatus();
    }
  }

  Future<void> loggedOut() async {
    if (!_running) return;
    _running = false;
    CallService.I.removeListener(_onCall);
    if (CallService.I.busy) CallService.I.hangup();
    ResponderController.I.incomingOffer.removeListener(_onOffer);
    DriverController.I.removeListener(_onDriver);
    CrashDetector.I.removeListener(_onDetector);
    _detectorServiceOn = false;
    await _chatSub?.cancel();
    DriverController.I.stop();
    ResponderController.I.stop();
    await CrashDetector.I.stop();
    DutyService.I.reset();
    await BackgroundService.I.stop();
    LocalAlerts.I.onTap = null;
    LocalAlerts.I.onActionDone = null;
    await LocalAlerts.I.cancelOffDuty();
    await LocalAlerts.I.cancelCrash();
    await AlarmService.I.stopAll();
    LiveService.I.stop();
    GpsService.I.stop();
  }

  /// The app came back to the front: catch up on what we missed.
  void resumed() {
    if (!_running) return;
    LiveService.I.requestSnapshot();
    if (Session.I.isResponder) {
      ResponderController.I.restore();
      DutyService.I.refresh(); // the shift may have ended while the app was away
    } else {
      DriverController.I.restore();
    }
  }

  /// A button on a notification was pressed (and its work is done): catch up with the server.
  void _onNotificationAction(String actionId) {
    resumed();
  }

  /// Crash detector on or off → background service on or off (driver).
  void _onDetector() {
    final on = CrashDetector.I.running;
    if (on == _detectorServiceOn) return;
    _detectorServiceOn = on;
    if (on) {
      BackgroundService.I.startDriver();
    } else {
      BackgroundService.I.stop();
    }
  }

  // ── full-screen pages ──

  DriverPhase _lastPhase = DriverPhase.idle;

  /// Opens the emergency page when an emergency STARTS (the driver may close it and come back
  /// from the red banner on the home screen; it does not jump back up by itself).
  void _onDriver() {
    final d = DriverController.I;
    final started =
        d.phase != DriverPhase.idle &&
        (_lastPhase == DriverPhase.idle ||
            _lastPhase == DriverPhase.finished ||
            d.phase == DriverPhase.countdown && _lastPhase != DriverPhase.countdown);
    _lastPhase = d.phase;
    if (started && !_flowOpen) openEmergency();
  }

  void openEmergency() {
    if (_flowOpen) return;
    _flowOpen = true;
    _push(const EmergencyFlowScreen(), fullscreen: true).then((_) => _flowOpen = false);
  }

  void _onOffer() {
    final o = ResponderController.I.incomingOffer.value;
    if (o != null && !_offerOpen) {
      _offerOpen = true;
      _push(const OfferScreen(), fullscreen: true).then((_) {
        _offerOpen = false;
        ResponderController.I.dismissOfferScreen();
      });
    }
  }

  void _onCall() {
    final c = CallService.I;
    if (c.busy && !_callOpen) {
      _callOpen = true;
      _push(const CallScreen(), fullscreen: true).then((_) => _callOpen = false);
    }
  }

  void openChat(String incidentId, {String? title}) {
    _push(ChatScreen(incidentId: incidentId, title: title));
  }

  void _onIncomingChat(ChatMessage m) {
    final messenger = messengerKey.currentState;
    if (messenger == null) return;
    AlarmService.I.beep();
    final incidentId = m.incidentId;
    final preview = m.isVoice ? '🎤 Voice message' : (m.text ?? '');
    messenger
      ..hideCurrentSnackBar()
      ..showSnackBar(
        SnackBar(
          backgroundColor: AppColors.ink,
          duration: const Duration(seconds: 6),
          content: Text('New message: $preview', maxLines: 2, overflow: TextOverflow.ellipsis),
          action: SnackBarAction(label: 'OPEN', textColor: Colors.white, onPressed: () => openChat(incidentId)),
        ),
      );
  }

  Future<T?> _push<T>(Widget page, {bool fullscreen = false}) async {
    final nav = navKey.currentState;
    if (nav == null) return null;
    return nav.push<T>(MaterialPageRoute(fullscreenDialog: fullscreen, builder: (_) => page));
  }
}
