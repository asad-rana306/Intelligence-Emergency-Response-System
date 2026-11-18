import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import '../services/duty_service.dart';
import '../core/config/app_settings.dart';
import '../core/net/api_client.dart';
import '../core/net/api_exception.dart';
import '../core/net/session.dart';
import '../core/utils/geo.dart';
import '../models/models.dart';
import '../services/alarm_service.dart';
import '../services/background_service.dart';
import '../services/call_service.dart';
import '../services/gps_service.dart';
import '../services/live_service.dart';
import '../services/local_alerts.dart';
import '../services/location_service.dart';

/// Duty states as the Auth service knows them. "Busy" = ON_MISSION.
class Duty {
  static const onDuty = 'ON_DUTY';
  static const busy = 'ON_MISSION';
  static const offDuty = 'OFF_DUTY';
}

/// The responder's side: duty, incoming requests, the current mission, history.
class ResponderController extends ChangeNotifier {
  ResponderController._();
  static final ResponderController I = ResponderController._();

  String duty = Duty.offDuty;
  bool dutyChanging = false;

  Offer? offer;

  /// The app opens the request screen when this changes to a new offer.
  final ValueNotifier<Offer?> incomingOffer = ValueNotifier(null);

  Incident? mission;
  bool bystander = false;
  bool actionBusy = false;
  String? notice;

  List<HistoryItem> history = [];
  bool historyLoading = false;
  String? historyError;

  double? myLat;
  double? myLng;
  bool locationOk = true;

  StreamSubscription? _events;
  StreamSubscription<Position>? _pos;
  Timer? _locTimer;
  Timer? _gpsTimer;

  bool get onMission => mission != null;

  /// Straight-line distance from me to the crash (mission or offer), or null.
  double? get distanceToCrash {
    final lat = mission?.lat ?? offer?.lat;
    final lng = mission?.lng ?? offer?.lng;
    if (lat == null || lng == null || myLat == null || myLng == null) return null;
    return Geo.meters(myLat!, myLng!, lat, lng);
  }

  void start() {
    duty = AppSettings.I.dutyStatus;
    _events ??= LiveService.I.events.listen(_onEvent);
    DutyService.I.addListener(_onDutyChanged);
    _startLocation();
    // The server decides who is on duty (1-hour shift, heartbeat). The phone follows it: a stored
    // "ON_DUTY" from yesterday must NOT start a new shift by itself.
    _syncDuty();
  }

  void stop() {
    DutyService.I.removeListener(_onDutyChanged);
    DutyService.I.reset();
    _seenServerStatus = null;
    _events?.cancel();
    _events = null;
    _pos?.cancel();
    _pos = null;
    _locTimer?.cancel();
    _gpsTimer?.cancel();
    GpsService.I.stop();
    offer = null;
    mission = null;
  }

  /// Asks the server for the real duty status and follows it.
  Future<void> _syncDuty({bool afterMission = false}) async {
    if (!afterMission) await restore();
    final server = await DutyService.I.refresh();
    if (server == null) return; // no connection: keep what the phone knew
    final before = duty;
    _seenServerStatus = server;
    _adopt(server);
    if (!afterMission && before == Duty.onDuty && server == Duty.offDuty) {
      notice = 'Your shift ended. Tap "On duty" to get requests again.';
      notifyListeners();
    }
  }

  /// Make the phone show [server] (ON_DUTY / ON_MISSION / OFF_DUTY) and run what it needs.
  void _adopt(String server) {
    duty = server;
    AppSettings.I.setDuty(server);
    if (server == Duty.offDuty) {
      DutyService.I.stop();
      BackgroundService.I.stop();
    } else {
      DutyService.I.start();
      BackgroundService.I.startResponder();
      LocalAlerts.I.cancelOffDuty();
    }
    notifyListeners();
  }

  String? _seenServerStatus;

  /// The server changed our status by itself (shift over, or the notification button "Go on duty").
  void _onDutyChanged() {
    final s = DutyService.I.status;
    if (s == _seenServerStatus) return; // only real changes, not the 1-second clock ticks
    _seenServerStatus = s;
    if (dutyChanging || onMission || s == null || s == duty) return;
    _adopt(s);
  }

  Future<void> restore() async {
    try {
      final o = await ApiClient.I.get('/api/dispatch/offers/current');
      if (o is Map) _showOffer(Offer.fromJson(Map<String, dynamic>.from(o)));
    } catch (_) {}
    try {
      final m = await ApiClient.I.get('/api/incidents/mine');
      if (m is Map) _startMission(Incident.fromJson(Map<String, dynamic>.from(m)));
    } catch (_) {}
  }

  // ───────────────────────────── duty ─────────────────────────────

  Future<String?> setDuty(String status, {bool force = false}) async {
    if (dutyChanging) return null;
    if (!force && status == duty && !onMission) return null;
    if (onMission && status == Duty.offDuty) return 'Finish the current emergency first.';
    dutyChanging = true;
    notifyListeners();
    try {
      final reply = await ApiClient.I.put('/api/responders/status', {'status': status});
      duty = status;
      _seenServerStatus = status;
      await AppSettings.I.setDuty(status);

      if (status == Duty.offDuty) {
        // The responder chose to stop: no heartbeat, no permanent notification, no alert.
        DutyService.I.stop();
        await BackgroundService.I.stop();
        await LocalAlerts.I.cancelOffDuty();
      } else {
        // ON duty = a new 1-hour shift. The answer says when it ends.
        if (reply is Map) DutyService.I.apply(Map<String, dynamic>.from(reply));
        DutyService.I.start();
        await LocalAlerts.I.cancelOffDuty();
        await BackgroundService.I.startResponder();
        await _sendLocationOnce();
      }
      return null;
    } on ApiException catch (e) {
      return e.message;
    } finally {
      dutyChanging = false;
      notifyListeners();
    }
  }

  // ───────────────────────────── offers ─────────────────────────────

  Future<String?> accept() async {
    final o = offer;
    if (o == null) return null;
    return _act(
      () async {
        await ApiClient.I.post('/api/dispatch/${o.incidentId}/accept');
        await AlarmService.I.stopLoop();
        offer = null;
        incomingOffer.value = null;
        await _loadMission(o.incidentId);
      },
      onConflict: () {
        offer = null;
        incomingOffer.value = null;
        notice = 'This request is no longer open.';
      },
    );
  }

  Future<String?> reject() async {
    final o = offer;
    if (o == null) return null;
    return _act(
      () async {
        await ApiClient.I.post('/api/dispatch/${o.incidentId}/reject');
        await AlarmService.I.stopLoop();
        offer = null;
        incomingOffer.value = null;
        notice = 'Rejected. It is in your history if you want to take it again.';
        loadHistory();
      },
      onConflict: () {
        offer = null;
        incomingOffer.value = null;
      },
    );
  }

  /// "Accept again" from the history.
  Future<String?> reaccept(String incidentId) => _act(() async {
    await ApiClient.I.post('/api/dispatch/$incidentId/reaccept');
    await AlarmService.I.stopLoop();
    offer = null;
    incomingOffer.value = null;
    await _loadMission(incidentId);
    loadHistory();
  });

  /// The request screen closed without an answer (it stays open on the server for 30 s).
  void dismissOfferScreen() {
    AlarmService.I.stopLoop();
    incomingOffer.value = null;
    notifyListeners();
  }

  // ───────────────────────────── mission ─────────────────────────────

  /// ACCEPTED → ARRIVED → EN_ROUTE_HOSPITAL → RESOLVED (RESOLVED also straight from ARRIVED).
  Future<String?> setStatus(String next, {String? hospital}) async {
    final m = mission;
    if (m == null) return null;
    return _act(() async {
      await ApiClient.I.post('/api/dispatch/${m.id}/status', {
        'status': next,
        if (hospital != null && hospital.trim().isNotEmpty) 'hospitalId': hospital.trim(),
      });
      if (next == 'RESOLVED') {
        _endMission('Mission completed. Thank you!');
      } else {
        mission = _withStatus(m, next);
      }
    });
  }

  Future<void> loadHistory() async {
    historyLoading = true;
    historyError = null;
    notifyListeners();
    try {
      final r = await ApiClient.I.get('/api/dispatch/history');
      if (r is List) {
        history = r.map((j) => HistoryItem.fromJson(Map<String, dynamic>.from(j as Map))).toList();
      }
    } on ApiException catch (e) {
      historyError = e.message;
    } finally {
      historyLoading = false;
      notifyListeners();
    }
  }

  void clearNotice() {
    notice = null;
    notifyListeners();
  }

  // ───────────────────────────── events ─────────────────────────────

  void _onEvent(LiveEvent e) {
    switch (e.type) {
      case 'SNAPSHOT':
        final o = e.data['offer'];
        final inc = e.data['incident'];
        if (o is Map && mission == null) _showOffer(Offer.fromJson(Map<String, dynamic>.from(o)));
        if (inc is Map && mission == null) {
          final id = inc['incidentId']?.toString();
          if (id != null) _loadMission(id);
        }
        break;
      case 'INCIDENT_OFFERED':
        _showOffer(Offer.fromJson(e.data));
        break;
      case 'INCIDENT_OFFER_EXPIRED':
        if (offer?.incidentId == e.incidentId) {
          AlarmService.I.stopLoop();
          offer = null;
          incomingOffer.value = null;
          notice = 'That request moved to another unit. You can still take it from History.';
          loadHistory();
          notifyListeners();
        }
        break;
      case 'INCIDENT_STAND_DOWN':
        if (mission?.id == e.incidentId || offer?.incidentId == e.incidentId) {
          AlarmService.I.stopLoop();
          if (CallService.I.busy) CallService.I.hangup();
          offer = null;
          incomingOffer.value = null;
          _endMission('The driver cancelled. You can stand down.');
          AlarmService.I.say('The driver cancelled. You can stand down.');
        }
        break;
      case 'BYSTANDER_ASSISTING':
        if (mission?.id == e.incidentId) {
          bystander = true;
          AlarmService.I.beep();
          notifyListeners();
        }
        break;
      case 'INCIDENT_STATUS':
        final m = mission;
        if (m != null && m.id == e.incidentId) {
          final s = e.data['status']?.toString() ?? m.status;
          if (s == 'RESOLVED') {
            _endMission('Mission completed.');
          } else {
            mission = _withStatus(m, s);
            notifyListeners();
          }
        }
        break;
    }
  }

  void _showOffer(Offer o) {
    if (o.incidentId.isEmpty || mission != null) return;
    if (offer?.incidentId == o.incidentId && incomingOffer.value != null) return;
    offer = o;
    incomingOffer.value = o;
    AlarmService.I.offerRing();
    notifyListeners();
  }

  Future<void> _loadMission(String id) async {
    try {
      final r = await ApiClient.I.get('/api/incidents/$id');
      if (r is Map) _startMission(Incident.fromJson(Map<String, dynamic>.from(r)));
    } catch (_) {
      _startMission(Incident(id: id, status: 'ACCEPTED'));
    }
  }

  void _startMission(Incident i) {
    if (i.id.isEmpty) return;
    mission = i;
    bystander = i.bystanderAssisting;
    duty = Duty.busy;
    _seenServerStatus = Duty.busy;
    AppSettings.I.setDuty(Duty.busy);
    // The heartbeat goes on during a mission (the server does not end the shift then).
    DutyService.I.start();
    BackgroundService.I.startResponder();
    _gpsTimer?.cancel();
    GpsService.I.startSending();
    _gpsTimer = Timer.periodic(const Duration(seconds: 4), (_) => _sendMissionGps());
    notifyListeners();
  }

  void _endMission(String message) {
    mission = null;
    bystander = false;
    _gpsTimer?.cancel();
    GpsService.I.stop();
    notice = message;
    loadHistory();
    notifyListeners();
    // Dispatch puts the responder back ON duty on the server. Ask what the server says (twice: the
    // first answer can come a moment before Dispatch has finished).
    _syncDuty(afterMission: true);
    Future.delayed(const Duration(seconds: 4), () {
      if (!onMission) DutyService.I.refresh();
    });
  }

  // ───────────────────────────── location ─────────────────────────────

  void _startLocation() async {
    if (!await LocationService.I.ensurePermission()) {
      locationOk = false;
      notifyListeners();
      return;
    }
    locationOk = true;
    _pos ??= LocationService.I.stream(distanceFilter: 10).listen((p) {
      myLat = p.latitude;
      myLng = p.longitude;
      notifyListeners();
    }, onError: (_) {});
    final p = await LocationService.I.current();
    if (p != null) {
      myLat = p.latitude;
      myLng = p.longitude;
      notifyListeners();
    }
    // Dispatch needs to know where free units are: every 15 s while on duty.
    _locTimer ??= Timer.periodic(const Duration(seconds: 15), (_) {
      if (duty != Duty.offDuty) _sendLocationOnce();
    });
  }

  Future<void> _sendLocationOnce() async {
    final lat = myLat, lng = myLng;
    if (lat == null || lng == null) return;
    try {
      await ApiClient.I.put('/api/responders/location', {'latitude': lat, 'longitude': lng});
    } catch (_) {}
  }

  void _sendMissionGps() {
    final m = mission;
    final lat = myLat, lng = myLng;
    final me = Session.I.userId;
    if (m == null || lat == null || lng == null || me == null) return;
    double? eta;
    if (m.lat != null && m.lng != null) {
      eta = Geo.roughEta(Geo.meters(lat, lng, m.lat!, m.lng!)).inSeconds / 60.0;
    }
    GpsService.I.send(
      m.id,
      me,
      lat,
      lng,
      speed: LocationService.I.last == null ? null : LocationService.kmh(LocationService.I.last!),
      heading: LocationService.I.last?.heading,
      etaMinutes: eta,
    );
  }

  // ───────────────────────────── helpers ─────────────────────────────

  Future<String?> _act(Future<void> Function() work, {VoidCallback? onConflict}) async {
    if (actionBusy) return null;
    actionBusy = true;
    notifyListeners();
    try {
      await work();
      return null;
    } on ApiException catch (e) {
      if (e.isConflict && onConflict != null) onConflict();
      return e.message;
    } finally {
      actionBusy = false;
      notifyListeners();
    }
  }

  static Incident _withStatus(Incident m, String s) => Incident(
    id: m.id,
    status: s,
    crashEventId: m.crashEventId,
    driverId: m.driverId,
    driverName: m.driverName,
    driverPhone: m.driverPhone,
    priorityScore: m.priorityScore,
    lat: m.lat,
    lng: m.lng,
    assignedResponderId: m.assignedResponderId,
    responder: m.responder,
    bloodType: m.bloodType,
    allergies: m.allergies,
    bystanderAssisting: m.bystanderAssisting,
    createdAt: m.createdAt,
  );
}