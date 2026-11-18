import 'dart:math';

class Geo {
  /// Straight-line distance in meters.
  static double meters(double lat1, double lng1, double lat2, double lng2) {
    const r = 6371000.0;
    final dLat = _rad(lat2 - lat1);
    final dLng = _rad(lng2 - lng1);
    final a = sin(dLat / 2) * sin(dLat / 2) + cos(_rad(lat1)) * cos(_rad(lat2)) * sin(dLng / 2) * sin(dLng / 2);
    return 2 * r * atan2(sqrt(a), sqrt(1 - a));
  }

  static double _rad(double d) => d * pi / 180;

  static String distanceText(double meters) =>
      meters < 1000 ? '${meters.round()} m' : '${(meters / 1000).toStringAsFixed(meters < 10000 ? 1 : 0)} km';

  /// Rough driving time in a city: road ≈ 1.4 × straight line, 30 km/h average.
  static Duration roughEta(double meters) => Duration(seconds: max(60, (meters * 1.4 / (30 / 3.6)).round()));

  static String etaText(Duration d) =>
      d.inMinutes < 60 ? '~${max(1, d.inMinutes)} min' : '~${d.inHours} h ${d.inMinutes % 60} min';
}
