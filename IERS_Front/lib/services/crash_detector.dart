import 'dart:async';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'package:sensors_plus/sensors_plus.dart';

import '../core/config/app_settings.dart';
import 'airbag_hint.dart';
import 'location_service.dart';

/// What the phone measured at a crash. Same units as the car box (contract):
/// gForce in g (max 16), rolloverAngle in degrees, speed in km/h, gyro in degrees per second.
class CrashReading {
  final double gForce;
  final double rolloverAngle;
  final double speedKmh;
  final double lat;
  final double lng;
  final Map<String, dynamic> sensorData;
  final String trigger; // IMPACT, ROLLOVER, SIMULATED
  CrashReading(this.gForce, this.rolloverAngle, this.speedKmh, this.lat, this.lng, this.sensorData, this.trigger);

  /// The body of POST /api/telemetry/crash/phone. The screen and the background service both use it.
  Map<String, dynamic> toRequest({
    double? lat,
    double? lng,
    bool manual = false,
    String? driverName,
    String? driverPhone,
  }) => {
    'gForce': gForce,
    'rolloverAngle': rolloverAngle,
    'speed': speedKmh,
    'gpsLat': lat ?? this.lat,
    'gpsLng': lng ?? this.lng,
    'timestamp': DateTime.now().millisecondsSinceEpoch,
    'sensorData': sensorData,
    'manual': manual,
    'driverName': driverName,
    'driverPhone': driverPhone,
  };
}

/// The phone works as the car box ("Drive mode").
///
/// It reports a crash when:
///  1. IMPACT: a hard hit (≥ 4 g on "normal") while the car was moving (≥ 15 km/h in the last 10 s),
///     and the phone was NOT falling just before (a dropped phone falls first, then hits the floor), or
///  2. ROLLOVER: the phone stays tilted more than 70° from its mounted position for 3 s after
///     the car was moving.
/// After a report it waits 60 s before it can report again.
/// The server still makes the final call, and the driver has 10 s to cancel.
///
/// It runs in two places: on the screen (normal) and, when the app is closed, inside the
/// background service (see background_service.dart, `headless: true`).
/// Every report also carries an airbag hint (software estimate, never a fact).
class CrashDetector extends ChangeNotifier {
  CrashDetector._();
  static final CrashDetector I = CrashDetector._();

  void Function(CrashReading reading)? onCrash;

  bool running = false;
  double speedKmh = 0;
  double liveG = 0; // shown on the drive screen
  double peakG = 0;
  double tilt = 0;
  bool gpsOk = false;

  final List<StreamSubscription> _subs = [];
  DateTime? _lastMovingAt;
  DateTime? _freeFallStart;
  DateTime? _freeFallEnd;
  bool _hitPending = false;
  DateTime? _lastJoltAt;
  DateTime? _tiltSince;
  List<double>? _baseline; // gravity direction when mounted
  final List<double> _gravity = [0, 0, 9.81];
  double _gyroRollPeak = 0;
  double _gyroPitchPeak = 0;
  final List<double> _lastAccel = [0, 0, 0];
  Timer? _uiTick;
  // GPS speed of the last seconds: the speed before a hit is the highest of these.
  final List<(DateTime, double)> _recentSpeeds = [];

  double get _threshold {
    switch (AppSettings.I.sensitivity) {
      case CrashSensitivity.low:
        return 6.0;
      case CrashSensitivity.high:
        return 2.8;
      case CrashSensitivity.normal:
        return 4.0;
    }
  }

  /// [headless] = running inside the background service (no screen to update).
  Future<void> start({bool headless = false}) async {
    if (running) return;
    running = true;
    _baseline = null;
    peakG = 0;
    _recentSpeeds.clear();
    if (!headless) notifyListeners();

    // Motion sensors (phone only). Chrome has no crash sensors, so skip them there
    // — the live speed/map still works from GPS below.
    if (!kIsWeb) {
      try {
        _subs.add(userAccelerometerEventStream(samplingPeriod: SensorInterval.gameInterval).listen(_onUserAccel));
        _subs.add(accelerometerEventStream(samplingPeriod: SensorInterval.uiInterval).listen(_onGravity));
        _subs.add(gyroscopeEventStream(samplingPeriod: SensorInterval.gameInterval).listen(_onGyro));
      } catch (_) {}
    }

    if (await LocationService.I.ensurePermission()) {
      gpsOk = true;
      _subs.add(
        LocationService.I
            .stream(distanceFilter: 0)
            .listen(
              _onPosition,
              onError: (_) {
                gpsOk = false;
              },
            ),
      );
    }
    if (!headless) _uiTick = Timer.periodic(const Duration(milliseconds: 400), (_) => notifyListeners());
  }

  Future<void> stop() async {
    running = false;
    for (final s in _subs) {
      await s.cancel();
    }
    _subs.clear();
    _uiTick?.cancel();
    _uiTick = null;
    notifyListeners();
  }

  /// For testing without a real crash (Settings → Developer tools).
  Future<void> simulate({double g = 12, double roll = 15}) async {
    final p = LocationService.I.last ?? await LocationService.I.current();
    final speed = max(speedKmh, 40.0);
    onCrash?.call(
      CrashReading(
        g,
        roll,
        speed,
        p?.latitude ?? 31.5204,
        p?.longitude ?? 74.3587,
        _sensorData(g, speedBefore: speed),
        'SIMULATED',
      ),
    );
  }

  // ── sensor handlers ──

  void _onPosition(Position p) {
    gpsOk = true;
    speedKmh = LocationService.kmh(p);
    final now = DateTime.now();
    if (speedKmh >= 15) _lastMovingAt = now;
    _recentSpeeds.add((now, speedKmh));
    _recentSpeeds.removeWhere((s) => now.difference(s.$1) > const Duration(seconds: 12));
  }

  bool get _wasMoving {
    if (AppSettings.I.devTools) return true; // desk testing
    final t = _lastMovingAt;
    return t != null && DateTime.now().difference(t) < const Duration(seconds: 10);
  }

  void _onGravity(AccelerometerEvent e) {
    // Low-pass filter → direction of gravity → how far the phone is tilted from its mount.
    const k = 0.1;
    _gravity[0] = _gravity[0] * (1 - k) + e.x * k;
    _gravity[1] = _gravity[1] * (1 - k) + e.y * k;
    _gravity[2] = _gravity[2] * (1 - k) + e.z * k;

    // Free fall: total acceleration near zero (phone dropped).
    final total = sqrt(e.x * e.x + e.y * e.y + e.z * e.z) / 9.81;
    final now = DateTime.now();
    if (total < 0.35) {
      _freeFallStart ??= now;
    } else if (_freeFallStart != null) {
      if (now.difference(_freeFallStart!) > const Duration(milliseconds: 120)) _freeFallEnd = now;
      _freeFallStart = null;
    }

    // Learn the mounted position while the car drives steadily.
    if (_baseline == null || (liveG < 0.3 && speedKmh > 5 && _tiltSince == null)) {
      final b = List<double>.from(_gravity);
      _baseline = _baseline == null ? b : [for (var i = 0; i < 3; i++) _baseline![i] * 0.98 + b[i] * 0.02];
    }
    tilt = _angle(_gravity, _baseline!);

    // A roll is violent: it needs a jolt (≥ 2 g) in the last 6 s, so picking the phone up
    // from its mount after parking never counts.
    final jolted = _lastJoltAt != null && now.difference(_lastJoltAt!) < const Duration(seconds: 6);
    final movedRecently = _lastMovingAt != null && now.difference(_lastMovingAt!) < const Duration(seconds: 20);
    if (tilt > 70 && jolted && (_wasMoving || movedRecently)) {
      _tiltSince ??= now;
      if (now.difference(_tiltSince!) > const Duration(seconds: 3)) {
        _tiltSince = null;
        _report(max(peakG, 2.0), tilt, 'ROLLOVER');
      }
    } else {
      _tiltSince = null;
    }
  }

  void _onUserAccel(UserAccelerometerEvent e) {
    final g = sqrt(e.x * e.x + e.y * e.y + e.z * e.z) / 9.81;
    liveG = g;
    _lastAccel[0] = e.x / 9.81;
    _lastAccel[1] = e.y / 9.81;
    _lastAccel[2] = e.z / 9.81;
    if (g > peakG) peakG = g;
    if (g >= 2.0) _lastJoltAt = DateTime.now();
    if (g < _threshold) return;

    final now = DateTime.now();
    final fellJustBefore =
        _freeFallStart != null ||
        (_freeFallEnd != null && now.difference(_freeFallEnd!) < const Duration(milliseconds: 1200));
    if (fellJustBefore) return; // dropped phone, not a crash
    if (!_wasMoving) return;

    // Many samples cross the line in one hit: only the first schedules a report.
    if (_hitPending) return;
    if (_lastReport != null && now.difference(_lastReport!) < const Duration(seconds: 60)) return;
    _hitPending = true;
    // Wait a moment to see the whole hit (and a possible roll), then report the peak.
    Future.delayed(const Duration(milliseconds: 1200), () {
      _hitPending = false;
      if (!running) return;
      _report(max(g, peakG), tilt, 'IMPACT');
    });
  }

  void _onGyro(GyroscopeEvent e) {
    final roll = e.x.abs() * 180 / pi;
    final pitch = e.y.abs() * 180 / pi;
    if (roll > _gyroRollPeak) _gyroRollPeak = roll;
    if (pitch > _gyroPitchPeak) _gyroPitchPeak = pitch;
  }

  DateTime? _lastReport;

  void _report(double g, double roll, String trigger) {
    final now = DateTime.now();
    if (_lastReport != null && now.difference(_lastReport!) < const Duration(seconds: 60)) return;
    _lastReport = now;
    final p = LocationService.I.last;
    final before = _lastSpeedBeforeHit();
    final reading = CrashReading(
      min(16, g),
      roll,
      before,
      p?.latitude ?? 0,
      p?.longitude ?? 0,
      _sensorData(g, speedBefore: before),
      trigger,
    );
    peakG = 0;
    _gyroRollPeak = 0;
    _gyroPitchPeak = 0;
    onCrash?.call(reading);
  }

  /// The highest GPS speed of the last 12 s (the speed just before the hit).
  double _lastSpeedBeforeHit() {
    var best = speedKmh;
    for (final s in _recentSpeeds) {
      if (s.$2 > best) best = s.$2;
    }
    return max(best, _wasMoving ? 15 : 0);
  }

  Map<String, dynamic> _sensorData(double g, {double? speedBefore}) {
    final before = speedBefore ?? _lastSpeedBeforeHit();
    final airbag = AirbagHint.estimate(peakG: g, speedBeforeKmh: before, speedAfterKmh: speedKmh);
    return {
      'accelX': double.parse(_lastAccel[0].toStringAsFixed(2)),
      'accelY': double.parse(_lastAccel[1].toStringAsFixed(2)),
      'accelZ': double.parse(_lastAccel[2].toStringAsFixed(2)),
      'gyroRoll': double.parse(_gyroRollPeak.toStringAsFixed(1)),
      'gyroPitch': double.parse(_gyroPitchPeak.toStringAsFixed(1)),
      'speedBefore': double.parse(before.toStringAsFixed(1)),
      'speedAfter': double.parse(speedKmh.toStringAsFixed(1)),
      'airbagEstimate': airbag.code, // NONE | POSSIBLE | LIKELY (a guess from the phone, never confirmed)
      'source': 'PHONE',
    };
  }

  static double _angle(List<double> a, List<double> b) {
    final dot = a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    final na = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2]);
    final nb = sqrt(b[0] * b[0] + b[1] * b[1] + b[2] * b[2]);
    if (na == 0 || nb == 0) return 0;
    return acos((dot / (na * nb)).clamp(-1.0, 1.0)) * 180 / pi;
  }
}
