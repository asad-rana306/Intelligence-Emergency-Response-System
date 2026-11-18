import 'dart:async';

import 'package:geolocator/geolocator.dart';

/// GPS position. Remembers the last fix so a crash always has a location, even if a new fix is slow.
class LocationService {
  LocationService._();
  static final LocationService I = LocationService._();

  Position? last;

  Future<bool> ensurePermission() async {
    if (!await Geolocator.isLocationServiceEnabled()) return false;
    var p = await Geolocator.checkPermission();
    if (p == LocationPermission.denied) p = await Geolocator.requestPermission();
    return p == LocationPermission.always || p == LocationPermission.whileInUse;
  }

  Future<bool> get serviceOn => Geolocator.isLocationServiceEnabled();

  /// A fix within [timeout], else the last known one, else null.
  Future<Position?> current({Duration timeout = const Duration(seconds: 6)}) async {
    try {
      if (!await ensurePermission()) return last ?? await Geolocator.getLastKnownPosition();
      final pos = await Geolocator.getCurrentPosition(
        locationSettings: const LocationSettings(accuracy: LocationAccuracy.high),
      ).timeout(timeout);
      last = pos;
      return pos;
    } catch (_) {
      return last ?? await _lastKnown();
    }
  }

  Future<Position?> _lastKnown() async {
    try {
      return await Geolocator.getLastKnownPosition();
    } catch (_) {
      return null;
    }
  }

  /// Positions while moving (every [distanceFilter] meters).
  Stream<Position> stream({int distanceFilter = 5}) =>
      Geolocator.getPositionStream(
        locationSettings: LocationSettings(accuracy: LocationAccuracy.high, distanceFilter: distanceFilter),
      ).map((p) {
        last = p;
        return p;
      });

  static double kmh(Position p) => p.speed.isNaN || p.speed < 0 ? 0 : p.speed * 3.6;

  Future<void> openSettings() => Geolocator.openLocationSettings();
}
