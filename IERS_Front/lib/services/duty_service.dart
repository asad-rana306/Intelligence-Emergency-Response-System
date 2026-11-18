import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/net/api_client.dart';
import 'local_alerts.dart';

/// The responder's shift, as the server sees it (Auth service).
///
/// Rules (all decided by the server, the app only follows them):
///  - Going ON duty starts a shift of 1 hour. After that the server puts the responder OFF duty.
///  - The phone must say "I am here" about every 30 seconds (heartbeat). 2 minutes of silence = OFF duty.
///  - Every heartbeat answer carries the status and the seconds left, so the app learns at once.
///
/// This class runs on the screen side. When the app is closed, the background service sends the
/// same heartbeat (see background_service.dart) and hands the answers to [apply].
class DutyService extends ChangeNotifier {
  DutyService._();
  static final DutyService I = DutyService._();

  static const onDuty = 'ON_DUTY';
  static const onMission = 'ON_MISSION';
  static const offDuty = 'OFF_DUTY';

  static const heartbeatEvery = Duration(seconds: 30);

  bool _running = false;
  Timer? _hb;
  Timer? _tick;

  /// The last status the server told us (ON_DUTY, ON_MISSION, OFF_DUTY) or null if we never heard.
  String? status;

  DateTime? startedAt;

  /// When the shift ends, in the PHONE's clock (made from "seconds remaining", so a wrong phone clock does not matter).
  DateTime? endsAt;

  /// True from the moment the server puts the responder OFF duty by itself, until the screen has shown it.
  bool wasAutoOffDuty = false;

  /// MAX_DURATION (shift over), HEARTBEAT_LOST (phone was silent) or null.
  String? autoOffReason;

  DateTime? _lastAutoOffAt;

  bool get running => _running;
  bool get isActive => status == onDuty || status == onMission;

  /// Seconds left in the shift (0 when there is none).
  int get secondsLeft {
    final e = endsAt;
    if (e == null || !isActive) return 0;
    final s = e.difference(DateTime.now()).inSeconds;
    return s < 0 ? 0 : s;
  }

  String get formattedTimeLeft => formatLeft(secondsLeft);

  /// "48m 12s", "1h 02m", or "—".
  static String formatLeft(int seconds) {
    if (seconds <= 0) return '—';
    final h = seconds ~/ 3600;
    final m = (seconds % 3600) ~/ 60;
    final s = seconds % 60;
    if (h > 0) return '${h}h ${m.toString().padLeft(2, '0')}m';
    return '${m}m ${s.toString().padLeft(2, '0')}s';
  }

  /// Short form for the permanent notification: "42 min".
  static String formatMinutes(int seconds) {
    if (seconds <= 0) return 'less than a minute';
    final m = (seconds + 59) ~/ 60; // round up: 0m 20s still shows "1 min"
    return '$m min';
  }

  // ───────────────────────────── start / stop ─────────────────────────────

  /// The responder is ON duty (or on a mission): send the heartbeat while the screen is alive.
  void start() {
    if (_running) return;
    _running = true;
    wasAutoOffDuty = false;
    _heartbeat();
    _hb = Timer.periodic(heartbeatEvery, (_) => _heartbeat());
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (isActive) notifyListeners();
    });
    notifyListeners();
  }

  /// The responder went OFF duty by choice (or logged out).
  void stop() {
    _stopTimers();
    status = offDuty;
    startedAt = null;
    endsAt = null;
    wasAutoOffDuty = false;
    notifyListeners();
  }

  /// Logout: forget everything.
  void reset() {
    _stopTimers();
    status = null;
    startedAt = null;
    endsAt = null;
    wasAutoOffDuty = false;
    autoOffReason = null;
    _lastAutoOffAt = null;
    notifyListeners();
  }

  void _stopTimers() {
    _running = false;
    _hb?.cancel();
    _hb = null;
    _tick?.cancel();
    _tick = null;
  }

  // ───────────────────────────── server answers ─────────────────────────────

  /// GET /api/responders/me — the truth. Returns the status, or null when the server cannot be reached.
  Future<String?> refresh() async {
    try {
      final r = await ApiClient.I.get('/api/responders/me');
      if (r is Map) {
        apply(Map<String, dynamic>.from(r));
        return status;
      }
    } catch (_) {}
    return null;
  }

  Future<void> _heartbeat() async {
    if (!_running) return;
    try {
      final r = await ApiClient.I.post('/api/responders/heartbeat');
      if (r is Map) apply(Map<String, dynamic>.from(r));
    } catch (_) {
      // A network blip: the next beat comes in 30 s. The server waits 2 minutes.
    }
  }

  /// Takes a ResponderProfileResponse (from /me, /status, /location or /heartbeat).
  void apply(Map<String, dynamic> p, {String? offReason}) {
    final next = p['dutyStatus']?.toString();
    if (next == null) return;
    final before = status;
    final wasActive = before == onDuty || before == onMission;
    final previousEnd = endsAt;
    status = next;
    startedAt = parseTime(p['dutyStartedAt']);

    final secs = (p['secondsRemaining'] as num?)?.toInt();
    if (secs != null) {
      endsAt = DateTime.now().add(Duration(seconds: secs));
    } else {
      endsAt = null;
    }

    if (next == offDuty && wasActive && _running) {
      // We thought we were on duty. The server says no. If the shift end was near, the hour is over;
      // otherwise the phone was silent.
      final shiftOver = previousEnd != null && DateTime.now().isAfter(previousEnd.subtract(const Duration(seconds: 90)));
      serverPutOffDuty(offReason ?? (shiftOver ? 'MAX_DURATION' : 'HEARTBEAT_LOST'));
      return;
    }
    notifyListeners();
  }

  /// The server put this responder OFF duty (a heartbeat answer, or the OFF_DUTY push).
  /// Shows the alert once: notification with sound and button, plus the dialog on the screen.
  void serverPutOffDuty(String? reason, {String? title, String? body}) {
    final now = DateTime.now();
    final last = _lastAutoOffAt;
    final repeat = last != null && now.difference(last) < const Duration(seconds: 90);
    _lastAutoOffAt = now;

    _stopTimers();
    status = offDuty;
    startedAt = null;
    endsAt = null;
    if (!repeat || reason != null) autoOffReason = reason ?? autoOffReason;
    if (!repeat) wasAutoOffDuty = true;
    notifyListeners();

    if (!repeat) {
      LocalAlerts.I.showOffDuty(reason: autoOffReason, title: title, body: body);
    }
  }

  /// The dialog was shown. (The notification stays until the responder goes on duty.)
  void ackAutoOffDuty() {
    wasAutoOffDuty = false;
    notifyListeners();
  }

  // ───────────────────────────── helpers ─────────────────────────────

  /// ISO text (the server sends Instants like 2026-11-18T10:34:00.123456789Z) or a number of seconds/ms.
  static DateTime? parseTime(Object? v) {
    if (v == null) return null;
    if (v is num) {
      final ms = v > 100000000000 ? v.toInt() : (v * 1000).toInt();
      return DateTime.fromMillisecondsSinceEpoch(ms);
    }
    return DateTime.tryParse(v.toString())?.toLocal();
  }
}
