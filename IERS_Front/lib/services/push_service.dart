import 'dart:ui' show DartPluginRegistrant;

import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import '../core/net/api_client.dart';
import '../core/net/session.dart';
import '../firebase_options.dart';
import 'local_alerts.dart';

/// Push messages from the server (Firebase Cloud Messaging). They reach the phone even when the app
/// is closed: a rescue request for a responder, "help is on the way" for a driver, "you are OFF duty".
///
/// Order of use:
///  1. main():            `await PushService.initFirebase();`        (once, before runApp)
///  2. app start:         `PushService.I.setup(onMessage: ...)`      (listeners)
///  3. after login:       `PushService.I.registerAfterLogin()`       (sends this phone's token to Auth)
///  4. before logout:     `PushService.I.unregisterBeforeLogout()`   (Auth stops pushing to this phone)
///
/// Only Android is set up. On Chrome and iOS everything here does nothing.
class PushService {
  PushService._();
  static final PushService I = PushService._();

  static bool _firebaseReady = false;
  bool _listening = false;
  String? _token;

  /// True when push works in this build (Android, Firebase started).
  bool get available => _firebaseReady;

  /// Starts Firebase. Never throws: without push the app still works.
  static Future<void> initFirebase() async {
    if (!DefaultFirebaseOptions.isSupported) return;
    try {
      await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform);
      FirebaseMessaging.onBackgroundMessage(onBackgroundMessage);
      _firebaseReady = true;
    } catch (e) {
      debugPrint('[push] Firebase did not start: $e');
    }
  }

  Future<void> setup({required Future<void> Function(RemoteMessage) onMessage}) async {
    if (!_firebaseReady || _listening) return;
    _listening = true;
    final messaging = FirebaseMessaging.instance;
    try {
      // Android 13+: asks for the notification permission.
      await messaging.requestPermission(alert: true, badge: true, sound: true);
    } catch (_) {}

    // App open: Android shows nothing by itself, so the app reacts.
    FirebaseMessaging.onMessage.listen(onMessage);
    // The user tapped the notification while the app was in the background.
    FirebaseMessaging.onMessageOpenedApp.listen(onMessage);
    // The app was closed and the notification tap started it.
    try {
      final initial = await messaging.getInitialMessage();
      if (initial != null) await onMessage(initial);
    } catch (_) {}

    messaging.onTokenRefresh.listen(_sendToken);
  }

  /// Sends this phone's token to Auth. Call after every login (and every app start while logged in).
  Future<void> registerAfterLogin() async {
    if (!_firebaseReady) return;
    try {
      final token = await FirebaseMessaging.instance.getToken();
      if (token == null || token.isEmpty) return;
      _token = token;
      await _sendToken(token);
    } catch (e) {
      debugPrint('[push] token fetch failed: $e');
    }
  }

  /// Tells Auth to stop pushing to this phone. Call BEFORE the login is cleared.
  Future<void> unregisterBeforeLogout() async {
    if (!_firebaseReady) return;
    try {
      final token = _token ?? await FirebaseMessaging.instance.getToken();
      if (token != null && token.isNotEmpty) {
        // Auth takes the token as a query value: DELETE /api/push/tokens?token=...
        await ApiClient.I.delete('/api/push/tokens', query: {'token': token});
      }
    } catch (e) {
      debugPrint('[push] unregister failed: $e');
    }
    _token = null;
  }

  Future<void> _sendToken(String token) async {
    if (!Session.I.isLoggedIn) return; // sent after the next login instead
    try {
      await ApiClient.I.post('/api/push/tokens', {'token': token, 'platform': 'android'});
    } catch (e) {
      debugPrint('[push] token not sent: $e');
    }
  }
}

/// A push message while the app is in the background or closed. MUST be top level.
///
/// A message with a visible "notification" (rescue request, help on the way) is shown by Android itself,
/// this function is not needed for it. OFF_DUTY is "data only": the app builds the alert here,
/// with the 5-second sound and the "Go on duty again" button.
@pragma('vm:entry-point')
Future<void> onBackgroundMessage(RemoteMessage msg) async {
  DartPluginRegistrant.ensureInitialized();
  try {
    await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform);
  } catch (_) {}
  final d = msg.data;
  if (d['type'] == 'OFF_DUTY') {
    await LocalAlerts.I.showOffDuty(reason: d['reason'], title: d['title'], body: d['body']);
  }
}
