import 'dart:async';
import 'dart:ui' show DartPluginRegistrant;

import 'package:flutter/foundation.dart';
import 'package:flutter_foreground_task/flutter_foreground_task.dart';

import '../core/config/app_config.dart';
import '../core/config/app_settings.dart';
import '../core/net/api_client.dart';
import '../core/net/session.dart';
import 'auth_service.dart';
import 'crash_detector.dart';
import 'duty_service.dart';
import 'local_alerts.dart';
import 'location_service.dart';

/// Keeps IERS alive when the app is closed or the screen is off (Android only).
///
/// A sticky notification shows at the top of the phone while it runs. There are two modes:
///
///  RESPONDER — keeps the duty alive:
///    - the heartbeat (every 30 s) and, when the screen is closed, the location (every 15 s),
///    - the notification shows "On duty — 42 min left",
///    - when the server puts the responder OFF duty (1 hour is over, or the phone was silent) it plays
///      the 5-second alert and the notification gets a "Go on duty" button.
///
///  DRIVER — keeps the crash sensors on:
///    - while the screen is open, the screen's own detector works (see crash_detector.dart),
///    - when the app is closed, the detector starts inside this service; a crash is sent to the
///      server and a notification with an "I'M OK" button opens (and wakes the screen).
///
/// The service runs in its own isolate (own memory). It talks to the screen with small messages:
///   screen → service: {'t':'ui'} every 4 s ("the screen is alive"), {'t':'mode', 'mode':...}
///   service → screen: {'t':'duty', 'profile': {...}}  (the heartbeat answer)
///
/// On iOS and Chrome nothing is started: sensors and heartbeat run only while the app is open.
class BackgroundService {
  BackgroundService._();
  static final BackgroundService I = BackgroundService._();

  static bool get supported => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  static const _modeKey = 'mode';
  static const buttonGoOnDuty = 'go_on';

  bool _initialized = false;
  bool _askedBattery = false;
  bool _starting = false;
  Timer? _ping;

  /// Call once in main() before runApp. [headless] = called from a notification button with no screen.
  void init({bool headless = false}) {
    if (_initialized || !supported) return;
    _initialized = true;
    try {
      if (!headless) FlutterForegroundTask.initCommunicationPort();
      FlutterForegroundTask.init(
        androidNotificationOptions: AndroidNotificationOptions(
          channelId: 'iers_duty',
          channelName: 'IERS is running',
          channelDescription: 'Shows that IERS is on duty or watching for crashes.',
          channelImportance: NotificationChannelImportance.LOW,
          priority: NotificationPriority.LOW,
          onlyAlertOnce: true,
        ),
        iosNotificationOptions: const IOSNotificationOptions(showNotification: false, playSound: false),
        foregroundTaskOptions: ForegroundTaskOptions(
          eventAction: ForegroundTaskEventAction.repeat(5000),
          autoRunOnBoot: false,
          allowWakeLock: true,
          allowWifiLock: true,
        ),
      );
      if (!headless) FlutterForegroundTask.addTaskDataCallback(_onTaskData);
    } catch (e) {
      debugPrint('[bg] init failed: $e');
    }
  }

  /// The responder is on duty: keep the heartbeat going, even with the app closed.
  /// [fromBackground] = started from a notification button (no screen, no permission pop-ups).
  Future<void> startResponder({bool fromBackground = false}) => _start('RESPONDER', fromBackground: fromBackground);

  /// The driver's phone is watching for crashes: keep the sensors on, even with the app closed.
  Future<void> startDriver() => _start('DRIVER');

  Future<void> stop() async {
    _ping?.cancel();
    _ping = null;
    if (!supported) return;
    try {
      if (await FlutterForegroundTask.isRunningService) await FlutterForegroundTask.stopService();
    } catch (e) {
      debugPrint('[bg] stop failed: $e');
    }
  }

  Future<bool> get isRunning async {
    if (!supported) return false;
    try {
      return await FlutterForegroundTask.isRunningService;
    } catch (_) {
      return false;
    }
  }

  /// Notification permission, and the "do not optimise battery" exemption (asked once per app start).
  Future<void> requestPermissions() async {
    if (!supported) return;
    try {
      final notif = await FlutterForegroundTask.checkNotificationPermission();
      if (notif != NotificationPermission.granted) {
        await FlutterForegroundTask.requestNotificationPermission();
      }
      if (!_askedBattery) {
        _askedBattery = true;
        if (!await FlutterForegroundTask.isIgnoringBatteryOptimizations) {
          await FlutterForegroundTask.requestIgnoreBatteryOptimization();
        }
      }
    } catch (e) {
      debugPrint('[bg] permissions: $e');
    }
  }

  // ───────────────────────────── internals ─────────────────────────────

  Future<void> _start(String mode, {bool fromBackground = false}) async {
    if (!supported || _starting) return;
    _starting = true;
    try {
      init(headless: fromBackground);
      if (!fromBackground) await requestPermissions();
      await FlutterForegroundTask.saveData(key: _modeKey, value: mode);

      final text = mode == 'RESPONDER' ? 'On duty — getting ready…' : 'Watching for crashes';
      if (await FlutterForegroundTask.isRunningService) {
        await FlutterForegroundTask.updateService(notificationTitle: 'IERS', notificationText: text);
        FlutterForegroundTask.sendDataToTask({'t': 'mode', 'mode': mode});
      } else {
        await FlutterForegroundTask.startService(
          notificationTitle: 'IERS',
          notificationText: text,
          notificationInitialRoute: '/',
          callback: startCallback,
        );
      }
      if (!fromBackground) _startPing();
    } catch (e) {
      // For example: location permission not granted yet. The app still works while it is open.
      debugPrint('[bg] start failed: $e');
    } finally {
      _starting = false;
    }
  }

  /// Tells the service "the screen is alive" so it does not do the screen's work twice.
  void _startPing() {
    _ping ??= Timer.periodic(const Duration(seconds: 4), (_) {
      try {
        FlutterForegroundTask.sendDataToTask({'t': 'ui'});
      } catch (_) {}
    });
    try {
      FlutterForegroundTask.sendDataToTask({'t': 'ui'});
    } catch (_) {}
  }

  /// Messages from the service.
  void _onTaskData(Object data) {
    if (data is Map && data['t'] == 'duty' && data['profile'] is Map) {
      final reason = data['offReason']?.toString();
      DutyService.I.apply(Map<String, dynamic>.from(data['profile'] as Map), offReason: reason);
      // The service saw the server put us OFF duty: make sure the alert is shown (it shows only once).
      if (reason != null && reason.isNotEmpty) DutyService.I.serverPutOffDuty(reason);
    }
  }
}

// ═════════════════════════════════════════════════════════════════════
//  The service itself. Everything below runs in the service's own isolate.
// ═════════════════════════════════════════════════════════════════════

/// Entry point of the service isolate. MUST be top level.
@pragma('vm:entry-point')
void startCallback() {
  DartPluginRegistrant.ensureInitialized();
  FlutterForegroundTask.setTaskHandler(IersTaskHandler());
}

class IersTaskHandler extends TaskHandler {
  String _mode = 'DRIVER';

  // The screen says "I am alive" every 4 s. Assume alive at the start (the screen started us).
  DateTime _uiSeen = DateTime.now();
  bool get _uiAlive => DateTime.now().difference(_uiSeen) < const Duration(seconds: 12);

  // responder
  String? _status;
  DateTime? _endsAt;
  bool _wasActive = false;
  DateTime _lastBeat = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime _lastLocation = DateTime.fromMillisecondsSinceEpoch(0);
  DateTime? _offSince;

  // both
  String _lastText = '';
  bool _busy = false;

  @override
  Future<void> onStart(DateTime timestamp, TaskStarter starter) async {
    try {
      await AppConfig.I.load();
      await AppSettings.I.load();
      await AuthService.I.load();
      _mode = await FlutterForegroundTask.getData<String>(key: 'mode') ?? 'DRIVER';
    } catch (e) {
      debugPrint('[fg] start: $e');
    }
    await _tick();
  }

  @override
  Future<void> onRepeatEvent(DateTime timestamp) => _tick();

  Future<void> _tick() async {
    if (_busy) return; // the last tick is still waiting for the network
    _busy = true;
    try {
      if (!Session.I.isLoggedIn) await Session.I.load();
      if (!Session.I.isLoggedIn) return; // logged out: the screen will stop us
      if (_mode == 'RESPONDER') {
        await _responderTick();
      } else {
        await _driverTick();
      }
    } catch (e) {
      debugPrint('[fg] tick: $e');
    } finally {
      _busy = false;
    }
  }

  // ───────────────────────────── responder ─────────────────────────────

  bool get _isActive => _status == 'ON_DUTY' || _status == 'ON_MISSION';

  Future<void> _responderTick() async {
    final now = DateTime.now();

    if (_status == null || _isActive) {
      if (now.difference(_lastBeat) >= DutyService.heartbeatEvery) {
        _lastBeat = now;
        await _heartbeat();
      }
      // With the screen closed nobody else sends the position (Dispatch needs it to pick the nearest unit).
      if (!_uiAlive && now.difference(_lastLocation) >= const Duration(seconds: 15)) {
        _lastLocation = now;
        await _sendLocation();
      }
    } else if (_offSince != null && now.difference(_offSince!) > const Duration(minutes: 30)) {
      // OFF duty and nobody pressed the button for 30 minutes: stop the service, the alert stays in the tray.
      await FlutterForegroundTask.stopService();
      return;
    }
    _refreshBanner();
  }

  Future<void> _heartbeat() async {
    try {
      final r = await ApiClient.I.post('/api/responders/heartbeat');
      if (r is Map) _apply(Map<String, dynamic>.from(r));
    } catch (_) {
      // A network blip. The next beat is in 30 s, the server waits 2 minutes.
    }
  }

  Future<void> _sendLocation() async {
    try {
      final p = await LocationService.I.current(timeout: const Duration(seconds: 5));
      if (p == null) return;
      final r = await ApiClient.I.put('/api/responders/location', {'latitude': p.latitude, 'longitude': p.longitude});
      if (r is Map) _apply(Map<String, dynamic>.from(r));
    } catch (_) {}
  }

  /// A ResponderProfileResponse from the server.
  void _apply(Map<String, dynamic> p) {
    final s = p['dutyStatus']?.toString();
    if (s == null) return;
    final secs = (p['secondsRemaining'] as num?)?.toInt();
    final previousEnd = _endsAt;
    _endsAt = secs == null ? null : DateTime.now().add(Duration(seconds: secs));
    final active = s == 'ON_DUTY' || s == 'ON_MISSION';

    String? offReason;
    if (_wasActive && s == 'OFF_DUTY') {
      _offSince = DateTime.now();
      // The shift end was close → the hour is over. Otherwise the phone was silent.
      final shiftOver = previousEnd != null && DateTime.now().isAfter(previousEnd.subtract(const Duration(seconds: 90)));
      offReason = shiftOver ? 'MAX_DURATION' : 'HEARTBEAT_LOST';
      // With the screen open, the screen shows the alert itself (it gets the same message below).
      if (!_uiAlive) LocalAlerts.I.showOffDuty(reason: offReason);
    }
    if (active) _offSince = null;
    _wasActive = active;
    _status = s;
    _lastText = ''; // redraw the notification
    try {
      FlutterForegroundTask.sendDataToMain({'t': 'duty', 'profile': p, 'offReason': offReason});
    } catch (_) {}
  }

  int get _secondsLeft {
    final e = _endsAt;
    if (e == null) return 0;
    final s = e.difference(DateTime.now()).inSeconds;
    return s < 0 ? 0 : s;
  }

  void _refreshBanner() {
    String text;
    List<NotificationButton>? buttons;
    if (_status == 'OFF_DUTY') {
      text = 'You are OFF duty. Tap "Go on duty" to get emergencies again.';
      buttons = const [NotificationButton(id: BackgroundService.buttonGoOnDuty, text: 'Go on duty')];
    } else if (_status == 'ON_MISSION') {
      text = 'On a mission';
      buttons = const [];
    } else if (_status == null) {
      text = 'On duty — checking…';
      buttons = const [];
    } else {
      text = 'On duty — ${DutyService.formatMinutes(_secondsLeft)} left';
      buttons = const [];
    }
    if (text == _lastText) return;
    _lastText = text;
    FlutterForegroundTask.updateService(notificationTitle: 'IERS', notificationText: text, notificationButtons: buttons);
  }

  /// The "Go on duty" button of the permanent notification.
  Future<void> _goOnDuty() async {
    try {
      final r = await ApiClient.I.put('/api/responders/status', {'status': 'ON_DUTY'});
      _wasActive = false; // coming back on duty must not look like "went off"
      if (r is Map) _apply(Map<String, dynamic>.from(r));
      _lastBeat = DateTime.now();
      _offSince = null;
      await LocalAlerts.I.cancelOffDuty();
    } catch (_) {
      await LocalAlerts.I.showInfo('Could not go on duty', 'No connection. Try again, or open the app.');
    }
  }

  // ───────────────────────────── driver ─────────────────────────────

  Future<void> _driverTick() async {
    final detector = CrashDetector.I;
    if (!_uiAlive && !detector.running) {
      // The screen is gone (app swiped away): the sensors move in here.
      await AppSettings.I.load(); // fresh sensitivity setting
      detector.onCrash = _onCrash;
      await detector.start(headless: true);
      _setText('Watching for crashes (app closed)');
    } else if (_uiAlive && detector.running) {
      // The screen is back and has its own detector.
      detector.onCrash = null;
      await detector.stop();
      _setText('Watching for crashes');
    }
  }

  void _setText(String text) {
    if (text == _lastText) return;
    _lastText = text;
    FlutterForegroundTask.updateService(notificationTitle: 'IERS', notificationText: text);
  }

  /// A crash seen with the app closed: tell the server, and tell the driver (siren + "I'M OK" button).
  Future<void> _onCrash(CrashReading r) async {
    final sending = _sendCrash(r);
    // Show at once (the siren must not wait for the network); the button needs the server's crash id.
    var id = await sending.timeout(const Duration(seconds: 2), onTimeout: () => null);
    await LocalAlerts.I.showCrash(id);
    if (id == null) {
      id = await sending;
      await LocalAlerts.I.showCrash(
        id,
        quiet: true,
        text: id == null ? 'Crash detected, but the server could not be reached. Call 1122 now.' : null,
      );
    }
  }

  Future<String?> _sendCrash(CrashReading r) async {
    for (var attempt = 0; attempt < 3; attempt++) {
      try {
        final reply = await ApiClient.I.post(
          '/api/telemetry/crash/phone',
          r.toRequest(driverName: AuthService.I.fullName, driverPhone: AuthService.I.phone),
        );
        final id = reply is Map ? reply['crashEventId']?.toString() : null;
        if (id != null && id.isNotEmpty) return id;
      } catch (_) {}
      await Future<void>.delayed(const Duration(seconds: 3));
    }
    return null;
  }

  // ───────────────────────────── messages and buttons ─────────────────────────────

  @override
  void onReceiveData(Object data) {
    if (data is! Map) return;
    switch (data['t']) {
      case 'ui':
        _uiSeen = DateTime.now();
        break;
      case 'mode':
        final m = data['mode'];
        if (m is String) {
          _mode = m;
          _lastText = '';
        }
        break;
    }
  }

  @override
  void onNotificationButtonPressed(String id) {
    if (id == BackgroundService.buttonGoOnDuty) _goOnDuty();
  }

  @override
  void onNotificationPressed() {
    FlutterForegroundTask.launchApp('/');
  }

  @override
  Future<void> onDestroy(DateTime timestamp, bool isTimeout) async {
    if (CrashDetector.I.running) {
      CrashDetector.I.onCrash = null;
      await CrashDetector.I.stop();
    }
  }
}
