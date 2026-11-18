import 'package:flutter/foundation.dart';
import 'package:geolocator/geolocator.dart';
import 'package:permission_handler/permission_handler.dart';

/// Web-safe permission helpers.
///
/// permission_handler crashes on the web for several permissions
/// (`checkPermissionStatus() has not been implemented ... on web`), so every call
/// here is guarded: on the web the browser asks for mic/camera/location on first use,
/// and there is nothing to request up front.
class Perms {
  Perms._();

  /// Ask for the phone permissions IERS uses. Safe to call anywhere.
  static Future<void> requestAll() async {
    // Location works on web too (via Geolocator) and is the one we really need.
    try {
      var p = await Geolocator.checkPermission();
      if (p == LocationPermission.denied) await Geolocator.requestPermission();
    } catch (_) {}
    if (kIsWeb) return; // the rest are native-only
    try {
      await [
        Permission.locationWhenInUse,
        Permission.microphone,
        Permission.phone,
        Permission.notification,
      ].request();
    } catch (_) {}
  }

  /// Just the microphone (before a call / voice note). Browser handles it on web.
  static Future<bool> microphone() async {
    if (kIsWeb) return true;
    try {
      final s = await Permission.microphone.request();
      return s.isGranted;
    } catch (_) {
      return true;
    }
  }

  static Future<void> ignoreBatteryOptimizations() async {
    if (kIsWeb) return;
    try {
      await Permission.ignoreBatteryOptimizations.request();
    } catch (_) {}
  }

  static Future<void> openSettings() async {
    try {
      if (kIsWeb) {
        await Geolocator.openAppSettings();
      } else {
        await openAppSettings();
      }
    } catch (_) {}
  }
}
