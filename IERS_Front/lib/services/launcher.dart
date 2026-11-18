import 'dart:io';

import 'package:flutter_phone_direct_caller/flutter_phone_direct_caller.dart';
import 'package:url_launcher/url_launcher.dart';

/// Opens other apps: maps, the phone dialer, SMS.
class Launcher {
  /// Turn-by-turn directions in Google Maps (or the browser if Maps is missing).
  static Future<bool> directions(double lat, double lng) async {
    if (Platform.isAndroid) {
      final nav = Uri.parse('google.navigation:q=$lat,$lng&mode=d');
      if (await _open(nav)) return true;
    }
    return _open(Uri.parse('https://www.google.com/maps/dir/?api=1&destination=$lat,$lng&travelmode=driving'));
  }

  /// Shows a place on the map.
  static Future<bool> showOnMap(double lat, double lng, {String label = 'Location'}) => _open(
    Uri.parse('geo:$lat,$lng?q=$lat,$lng(${Uri.encodeComponent(label)})'),
  ).then((ok) => ok ? ok : _open(Uri.parse('https://www.google.com/maps/search/?api=1&query=$lat,$lng')));

  static Future<bool> nearbyHospitals(double? lat, double? lng) {
    final at = lat != null && lng != null ? '$lat,$lng' : '0,0';
    return _open(
      Uri.parse('geo:$at?q=hospital'),
    ).then((ok) => ok ? ok : _open(Uri.parse('https://www.google.com/maps/search/hospital/@$at,14z')));
  }

  /// Opens the dialer with the number (the user presses call).
  static Future<bool> dial(String number) => _open(Uri(scheme: 'tel', path: _clean(number)));

  /// Calls straight away (needs the phone permission). Falls back to the dialer.
  static Future<bool> callNow(String number) async {
    final n = _clean(number);
    if (n.isEmpty) return false;
    try {
      final ok = await FlutterPhoneDirectCaller.callNumber(n);
      if (ok == true) return true;
    } catch (_) {}
    return dial(n);
  }

  /// Opens the SMS app with the text ready (works without mobile data).
  static Future<bool> sms(List<String> numbers, String text) {
    final to = numbers.map(_clean).where((n) => n.isNotEmpty).join(Platform.isIOS ? ',' : ';');
    return _open(Uri.parse('sms:$to?body=${Uri.encodeComponent(text)}'));
  }

  static String mapsLink(double lat, double lng) => 'https://maps.google.com/?q=$lat,$lng';

  static String _clean(String n) => n.replaceAll(RegExp(r'[^0-9+*#]'), '');

  static Future<bool> _open(Uri uri) async {
    try {
      return await launchUrl(uri, mode: LaunchMode.externalApplication);
    } catch (_) {
      return false;
    }
  }
}
