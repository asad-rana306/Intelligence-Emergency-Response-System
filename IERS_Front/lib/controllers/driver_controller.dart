import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/config/app_config.dart';
import '../core/config/app_settings.dart';
import '../core/config/emergency_numbers.dart';
import '../core/net/api_client.dart';
import '../core/net/api_exception.dart';
import '../models/models.dart';
import '../services/alarm_service.dart';
import '../services/auth_service.dart';
import '../services/call_service.dart';
import '../services/crash_detector.dart';
import '../services/gps_service.dart';
import '../services/launcher.dart';
import '../services/live_service.dart';
import '../services/local_history.dart';
import '../services/location_service.dart';
import '../services/profile_service.dart';

/// Where the driver is in an emergency.
enum DriverPhase {
  idle, // nothing going on
  countdown, // crash detected: 10 s to cancel (alarm on)
  requesting, // window over: help is being requested (waiting for the incident)
  offline, // window over but the crash never reached the server (no internet)
  incident, // Dispatch is handling it (finding / sending a unit)
  finished, // resolved (the screen shows "you are safe")
}

/// The driver's whole emergency flow, from the crash to the end.
/// Fed by the phone's own crash detector, the SOS button, and live events (car box, Dispatch).
class DriverController extends ChangeNotifier {
  DriverController._();
  static final DriverController I = DriverController._();

  DriverPhase phase = DriverPhase.idle;
  bool manualSos = false;
  String source = 'PHONE'; // PHONE or BOX

  // crash
  String? crashEventId;
  DateTime? windowEnds;
  bool _cancelWanted = false;
  bool cancelBusy = false;
  String? sendProblem;
  CrashReading? _unsent;

  // incident
  String? incidentId;
  String status = 'DISPATCHING';
  ResponderInfo? responder;
  bool noResponder = false;
  bool bystanderMarked = false;
  double? crashLat;
  double? crashLng;
  double? unitLat;
  double? unitLng;
  double? etaMinutes;
  String? notice; // one-line message for the home screen ("Not serious, no help sent")

  // announcements
  bool announceEnabled = true;
  bool announceDisabledForever = false; // the driver pressed "stop announcement" for this accident
  bool get announcing => AlarmService.I.isShouting;

  Timer? _tick;
  Timer? _resend;
  Timer? _quietTimer;
  StreamSubscription? _events;
  bool _autoCalled = false;
  bool _rescueDialed = false;
  DateTime? requestingSince;

  bool get active => phase != DriverPhase.idle;
  bool get inWindow => phase == DriverPhase.countdown;
  Duration get remaining {
    final w = windowEnds;
    if (w == null) return Duration.zero;
    final r = w.difference(DateTime.now());
    return r.isNegative ? Duration.zero : r;
  }

  void start() {
    _events ??= LiveService.I.events.listen(_onEvent);
    CrashDetector.I.onCrash = (r) => reportCrash(r);
    restore();
  }

  void stop() {
    _events?.cancel();
    _events = null;
    _reset();
  }

  /// After a restart or coming back online: is there an open incident?
  Future<void> restore() async {
    try {
      final r = await ApiClient.I.get('/api/incidents/mine');
      if (r is Map) _adoptIncident(Incident.fromJson(Map<String, dynamic>.from(r)));
    } catch (_) {}
  }

  // ───────────────────────────── triggers ─────────────────────────────

  /// The phone (drive mode) detected a crash.
  Future<void> reportCrash(CrashReading r, {bool manual = false}) async {
    if (phase == DriverPhase.countdown || phase == DriverPhase.requesting || phase == DriverPhase.incident) return;
    _reset(keepNotice: false);
    manualSos = manual;
    source = 'PHONE';
    _unsent = r;
    crashLat = r.lat != 0 ? r.lat : null;
    crashLng = r.lng != 0 ? r.lng : null;
    windowEnds = DateTime.now().add(AppConfig.cancelWindow);
    _enter(DriverPhase.countdown);
    AlarmService.I.siren();
    LocalHistory.I.add(
      manual ? 'SOS sent' : 'Crash detected by phone',
      manual ? 'SOS' : 'CRASH',
      detail: manual ? null : '${r.gForce.toStringAsFixed(1)} g · ${r.speedKmh.round()} km/h',
    );
    _startTick();
    await _sendCrash();
  }

  /// The driver held the SOS button.
  Future<void> sos() async {
    final p = await LocationService.I.current(timeout: const Duration(seconds: 4));
    await reportCrash(
      CrashReading(0, 0, CrashDetector.I.speedKmh, p?.latitude ?? 0, p?.longitude ?? 0, {'source': 'PHONE_SOS'}, 'SOS'),
      manual: true,
    );
  }

  // ───────────────────────────── driver actions ─────────────────────────────

  /// "I'M OK" inside the 10 seconds.
  Future<void> cancel() async {
    _cancelWanted = true;
    cancelBusy = true;
    notifyListeners();
    await AlarmService.I.stopAll();
    final id = crashEventId;
    if (id == null) {
      // Never reached the server: just stop trying.
      _resend?.cancel();
      _unsent = null;
      LocalHistory.I.add('Cancelled — I am OK', 'CANCEL');
      _finishQuietly('Cancelled. No help was sent.');
      return;
    }
    try {
      final r = await ApiClient.I.post('/api/telemetry/crash/$id/cancel');
      final s = (r is Map ? r['status'] : null)?.toString();
      if (s == 'TOO_LATE') {
        await lateCancel();
        return;
      }
      LocalHistory.I.add('Cancelled — I am OK', 'CANCEL');
      _finishQuietly('Cancelled. No help was sent.');
    } on ApiException catch (e) {
      cancelBusy = false;
      sendProblem = e.isOffline ? 'No internet: the cancel could not be sent. Trying again…' : e.message;
      notifyListeners();
      if (e.isOffline) _resend = Timer(const Duration(seconds: 3), cancel);
    }
  }

  /// "I'm OK, cancel help" after the window (stands the responder down).
  Future<void> lateCancel() async {
    final id = crashEventId;
    cancelBusy = true;
    notifyListeners();
    await AlarmService.I.stopAll();
    try {
      if (id != null) await ApiClient.I.post('/api/telemetry/crash/$id/late-cancel');
      if (CallService.I.busy) CallService.I.hangup();
      LocalHistory.I.add('Help cancelled — I am OK', 'CANCEL', incidentId: incidentId);
      _finishQuietly('Help cancelled. The unit was told to stand down.');
    } on ApiException catch (e) {
      cancelBusy = false;
      sendProblem = e.message;
      notifyListeners();
    }
  }

  /// A bystander pressed "I'm helping": tell the responder and start a call.
  Future<void> bystanderHelping() async {
    touched(stopAnnouncing: true);
    final id = incidentId;
    if (id == null) return;
    bystanderMarked = true;
    notifyListeners();
    try {
      await ApiClient.I.post('/api/incidents/$id/bystander-assist');
    } catch (_) {}
    if (status != 'DISPATCHING' && status != 'DISPATCHED' && responder != null) {
      callResponder();
    }
  }

  void callResponder({bool auto = false}) {
    final id = incidentId;
    if (id == null) return;
    CallService.I.call(incidentId: id, name: responder?.name ?? 'Rescue unit', phone: responder?.phone, auto: auto);
  }

  Future<void> callRescue() => Launcher.callNow(EmergencyNumbers.rescue.number);

  /// Opens SMS to all emergency contacts with the location (works without mobile data).
  Future<void> smsContacts() async {
    final numbers = ProfileService.I.contacts.map((c) => c.phone).toList();
    final lat = crashLat ?? LocationService.I.last?.latitude;
    final lng = crashLng ?? LocationService.I.last?.longitude;
    final where = lat != null && lng != null ? ' Location: ${Launcher.mapsLink(lat, lng)}' : '';
    final name = AuthService.I.fullName ?? 'I';
    await Launcher.sms(numbers, 'EMERGENCY: $name had a car accident.$where Please call 1122 and call me.');
  }

  /// Any touch on the emergency screen: someone is there, so no loud announcement now.
  /// (It can come back later if nobody keeps touching — unless it was stopped for good.)
  void touched({bool stopAnnouncing = false}) {
    if (stopAnnouncing) announceEnabled = false;
    if (AlarmService.I.isShouting) AlarmService.I.stopShout();
    _armAnnouncement();
    notifyListeners();
  }

  /// Button: stop the loud announcement for this whole accident. It will NOT start again.
  void stopAnnouncementForever() {
    announceDisabledForever = true;
    announceEnabled = false;
    _quietTimer?.cancel();
    AlarmService.I.stopShout();
    notifyListeners();
  }

  void toggleAnnouncement() {
    if (AlarmService.I.isShouting) {
      stopAnnouncementForever();
    } else {
      announceDisabledForever = false;
      announceEnabled = true;
      AlarmService.I.startShout();
    }
    notifyListeners();
  }

  /// The screen's "Done" after the end.
  void done() => _reset();

  // ───────────────────────────── server ─────────────────────────────

  Future<void> _sendCrash() async {
    final r = _unsent;
    if (r == null || _cancelWanted) return;
    final p = (r.lat == 0 && r.lng == 0) ? (LocationService.I.last ?? await LocationService.I.current()) : null;
    final lat = p?.latitude ?? r.lat;
    final lng = p?.longitude ?? r.lng;
    crashLat ??= lat == 0 ? null : lat;
    crashLng ??= lng == 0 ? null : lng;
    try {
      final reply = await ApiClient.I.post(
        '/api/telemetry/crash/phone',
        r.toRequest(
          lat: lat,
          lng: lng,
          manual: manualSos,
          driverName: AuthService.I.fullName,
          driverPhone: AuthService.I.phone,
        ),
      );
      _unsent = null;
      sendProblem = null;
      if (reply is Map) {
        final id = reply['crashEventId']?.toString();
        if (id != null) crashEventId = id;
        _syncWindow(reply['windowEndsAt']?.toString(), reply['serverTime']?.toString());
      }
      if (_cancelWanted) {
        await cancel();
        return;
      }
      if (phase == DriverPhase.offline) _enter(DriverPhase.requesting);
      notifyListeners();
    } on ApiException catch (e) {
      sendProblem = e.isOffline ? 'No internet. Trying again…' : 'Could not send: ${e.message}';
      notifyListeners();
      _resend?.cancel();
      _resend = Timer(const Duration(seconds: 3), _sendCrash);
    }
  }

  void _onEvent(LiveEvent e) {
    switch (e.type) {
      case 'SNAPSHOT':
        final crash = e.data['crash'];
        final inc = e.data['incident'];
        if (inc is Map && phase != DriverPhase.finished) {
          final m = Map<String, dynamic>.from(inc);
          _adoptIncident(
            Incident(
              id: m['incidentId']?.toString() ?? '',
              crashEventId: m['crashEventId']?.toString(),
              status: m['status']?.toString() ?? 'DISPATCHING',
              responder: m['responder'] == null ? null : ResponderInfo.fromJson(m['responder']),
            ),
            noResponderNow: m['noResponderAvailable'] == true,
          );
        } else if (crash is Map && phase == DriverPhase.idle) {
          _boxCrash(Map<String, dynamic>.from(crash), e.serverTime);
        }
        break;
      case 'CRASH_PENDING':
        if (crashEventId == null && phase == DriverPhase.countdown) {
          crashEventId = e.crashEventId; // our own phone crash
          _syncWindow(e.data['windowEndsAt']?.toString(), e.serverTime?.toUtc().toIso8601String());
        } else if (crashEventId != e.crashEventId && phase == DriverPhase.idle) {
          _boxCrash(e.data, e.serverTime);
        }
        notifyListeners();
        break;
      case 'CRASH_DISMISSED':
        if (e.crashEventId != null && e.crashEventId != crashEventId) return;
        final reason = e.data['reason']?.toString();
        if (reason == 'ML_NOT_SEVERE') {
          LocalHistory.I.add('Not a serious crash — no help sent', 'INFO');
          _finishQuietly('It did not look like a serious crash, so no help was sent. Need help? Hold SOS.');
        } else if (phase == DriverPhase.countdown || phase == DriverPhase.requesting) {
          _finishQuietly(reason == 'CANCELLED_BY_DEVICE' ? 'Cancelled with the button on the car box.' : 'Cancelled.');
        }
        break;
      case 'INCIDENT_CREATED':
        _adoptIncident(Incident(id: e.incidentId ?? '', crashEventId: e.crashEventId, status: 'DISPATCHING'));
        break;
      case 'INCIDENT_ACCEPTED':
        if (e.incidentId != null && incidentId != null && e.incidentId != incidentId) return;
        incidentId ??= e.incidentId;
        status = 'ACCEPTED';
        noResponder = false;
        responder = ResponderInfo.fromJson(e.data['responder']);
        if (phase != DriverPhase.incident) _enter(DriverPhase.incident);
        LocalHistory.I.add(
          'Rescue unit accepted',
          'HELP',
          detail: [responder?.name, responder?.vehicleId].whereType<String>().join(' · '),
          incidentId: incidentId,
        );
        _onAccepted();
        notifyListeners();
        break;
      case 'INCIDENT_STATUS':
        if (e.incidentId != incidentId) return;
        status = e.data['status']?.toString() ?? status;
        if (status == 'RESOLVED') {
          LocalHistory.I.add('Emergency completed', 'DONE', incidentId: incidentId);
          AlarmService.I.stopAll();
          GpsService.I.stop();
          _enter(DriverPhase.finished);
        } else if (status == 'ARRIVED') {
          AlarmService.I.stopShout();
          LocalHistory.I.add('Rescue unit arrived', 'HELP', incidentId: incidentId);
        }
        notifyListeners();
        break;
      case 'NO_RESPONDER_AVAILABLE':
        if (e.incidentId != incidentId) return;
        noResponder = true;
        notifyListeners();
        if (AppSettings.I.autoDialRescue && !_rescueDialed) {
          _rescueDialed = true;
          callRescue();
        }
        break;
    }
  }

  void _boxCrash(Map<String, dynamic> d, DateTime? serverTime) {
    final ends = d['windowEndsAt']?.toString();
    if (ends == null) return;
    _reset(keepNotice: false);
    source = 'BOX';
    crashEventId = d['crashEventId']?.toString();
    _syncWindow(ends, serverTime?.toUtc().toIso8601String());
    if (remaining == Duration.zero) {
      _enter(DriverPhase.requesting);
      return;
    }
    _enter(DriverPhase.countdown);
    AlarmService.I.siren();
    LocalHistory.I.add('Crash detected by car box', 'CRASH');
    _startTick();
  }

  void _adoptIncident(Incident i, {bool noResponderNow = false}) {
    if (i.id.isEmpty) return;
    final isNew = incidentId != i.id;
    incidentId = i.id;
    crashEventId ??= i.crashEventId;
    status = i.status;
    if (i.responder != null && !i.responder!.isEmpty) responder = i.responder;
    if (noResponderNow) noResponder = true;
    if (i.lat != null) crashLat = i.lat;
    if (i.lng != null) crashLng = i.lng;
    bystanderMarked = bystanderMarked || i.bystanderAssisting;
    _tick?.cancel();
    AlarmService.I.stopLoop();
    _enter(DriverPhase.incident);
    if (isNew) {
      LocalHistory.I.add(manualSos ? 'Help requested (SOS)' : 'Help requested', 'HELP', incidentId: i.id);
      _loadIncidentDetails(i.id);
      GpsService.I.watch(i.id, (lat, lng, eta) {
        unitLat = lat;
        unitLng = lng;
        etaMinutes = eta;
        notifyListeners();
      });
      _armAnnouncement();
    }
    if (status == 'ACCEPTED' && responder != null) _onAccepted();
    notifyListeners();
  }

  Future<void> _loadIncidentDetails(String id) async {
    try {
      final r = await ApiClient.I.get('/api/incidents/$id');
      if (r is Map) {
        final i = Incident.fromJson(Map<String, dynamic>.from(r));
        crashLat = i.lat ?? crashLat;
        crashLng = i.lng ?? crashLng;
        if (i.responder != null && !i.responder!.isEmpty) responder = i.responder;
        status = i.status;
        notifyListeners();
      }
    } catch (_) {}
  }

  void _onAccepted() {
    if (_autoCalled) return;
    _autoCalled = true;
    AlarmService.I.stopShout();
    AlarmService.I.say('Help is on the way.');
    if (AppSettings.I.autoCallResponder) {
      Future.delayed(const Duration(seconds: 4), () {
        if (phase == DriverPhase.incident && !CallService.I.busy) callResponder(auto: true);
      });
    }
  }

  // ───────────────────────────── helpers ─────────────────────────────

  /// Count down to the server's end time, not the phone's own clock.
  void _syncWindow(String? windowEndsAt, String? serverTime) {
    final ends = windowEndsAt == null ? null : DateTime.tryParse(windowEndsAt);
    if (ends == null) return;
    final server = serverTime == null ? null : DateTime.tryParse(serverTime);
    final offset = server == null ? Duration.zero : server.difference(DateTime.now());
    windowEnds = ends.subtract(offset).toLocal();
  }

  void _startTick() {
    _tick?.cancel();
    _tick = Timer.periodic(const Duration(milliseconds: 250), (_) {
      if (phase != DriverPhase.countdown) {
        _tick?.cancel();
        return;
      }
      if (remaining == Duration.zero) {
        _tick?.cancel();
        AlarmService.I.stopLoop();
        if (crashEventId == null && _unsent != null) {
          _enter(DriverPhase.offline);
        } else {
          _enter(DriverPhase.requesting);
        }
        _armAnnouncement();
      }
      notifyListeners();
    });
  }

  /// After [shoutDelaySeconds] without a touch, announce loudly for bystanders.
  void _armAnnouncement() {
    _quietTimer?.cancel();
    if (announceDisabledForever) return;
    if (!AppSettings.I.shoutForHelp || !announceEnabled) return;
    if (phase != DriverPhase.requesting && phase != DriverPhase.incident && phase != DriverPhase.offline) return;
    _quietTimer = Timer(Duration(seconds: AppSettings.I.shoutDelaySeconds), () {
      if (!announceEnabled || status == 'ARRIVED' || status == 'EN_ROUTE_HOSPITAL') return;
      if (phase == DriverPhase.requesting || phase == DriverPhase.incident || phase == DriverPhase.offline) {
        if (!CallService.I.busy) AlarmService.I.startShout();
        notifyListeners();
      }
    });
  }

  void _enter(DriverPhase p) {
    phase = p;
    if (p == DriverPhase.requesting) requestingSince = DateTime.now();
    notifyListeners();
  }

  void _finishQuietly(String message) {
    _reset();
    notice = message;
    notifyListeners();
  }

  void _reset({bool keepNotice = true}) {
    _tick?.cancel();
    _resend?.cancel();
    _quietTimer?.cancel();
    AlarmService.I.stopAll();
    GpsService.I.stop();
    phase = DriverPhase.idle;
    manualSos = false;
    crashEventId = null;
    windowEnds = null;
    _cancelWanted = false;
    cancelBusy = false;
    sendProblem = null;
    _unsent = null;
    incidentId = null;
    status = 'DISPATCHING';
    responder = null;
    noResponder = false;
    bystanderMarked = false;
    crashLat = crashLng = unitLat = unitLng = etaMinutes = null;
    announceEnabled = true;
    announceDisabledForever = false;
    _autoCalled = false;
    _rescueDialed = false;
    requestingSince = null;
    if (!keepNotice) notice = null;
    notifyListeners();
  }

  void clearNotice() {
    notice = null;
    notifyListeners();
  }
}
