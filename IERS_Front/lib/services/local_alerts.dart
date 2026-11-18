import 'dart:ui' show DartPluginRegistrant;

import 'package:flutter/foundation.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';

import '../core/config/app_config.dart';
import '../core/net/api_client.dart';
import '../core/net/api_exception.dart';
import '../core/net/session.dart';
import 'background_service.dart';

/// Notifications the app makes itself (Android only):
///
///  - the Android channels the server's push messages use (`iers_offers`, `iers_info`),
///  - "You are now OFF duty": a 5-second sound and a "Go on duty again" button,
///  - "Crash detected" when the phone sees a crash while the app is closed, with an "I'M OK" button.
///
/// It works on the screen and also from the background service and from a closed app, so the
/// button code (`runAction`) does its own login and server address loading.
class LocalAlerts {
  LocalAlerts._();
  static final LocalAlerts I = LocalAlerts._();

  // The server's push messages name these two channels, so the names must not change.
  static const channelOffers = 'iers_offers';
  static const channelInfo = 'iers_info';
  static const channelDuty = 'iers_duty_alert';
  static const channelCrash = 'iers_crash';

  static const idOffDuty = 4001;
  static const idCrash = 4002;
  static const idInfo = 4003;

  static const actionGoOnDuty = 'GO_ON_DUTY';
  static const actionImOk = 'IM_OK';

  final FlutterLocalNotificationsPlugin _plugin = FlutterLocalNotificationsPlugin();
  Future<void>? _init;

  /// Screen only: the user tapped a notification (not a button). [payload] says which one.
  void Function(String? payload)? onTap;

  /// Screen only: a button was pressed and its work is done (the screen refreshes itself).
  void Function(String actionId)? onActionDone;

  static bool get supported => !kIsWeb && defaultTargetPlatform == TargetPlatform.android;

  /// Safe to call many times, from any isolate.
  Future<void> init() {
    if (!supported) return Future.value();
    return _init ??= _doInit();
  }

  Future<void> _doInit() async {
    try {
      await _plugin.initialize(
        const InitializationSettings(android: AndroidInitializationSettings('@mipmap/ic_launcher')),
        onDidReceiveNotificationResponse: _onForegroundResponse,
        onDidReceiveBackgroundNotificationResponse: notificationTapBackground,
      );
      final android = _plugin.resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();
      if (android == null) return;
      // Android keeps a channel's sound for good once it is created, so these names are final.
      await android.createNotificationChannel(
        AndroidNotificationChannel(
          channelOffers,
          'Emergency requests',
          description: 'A rescue request for responders. Loud, shows on top.',
          importance: Importance.max,
          sound: const RawResourceAndroidNotificationSound('iers_offer'),
          audioAttributesUsage: AudioAttributesUsage.alarm,
        ),
      );
      await android.createNotificationChannel(
        AndroidNotificationChannel(
          channelInfo,
          'Updates',
          description: 'Help is on the way, request cancelled, and similar messages.',
          importance: Importance.high,
        ),
      );
      await android.createNotificationChannel(
        AndroidNotificationChannel(
          channelDuty,
          'Duty alerts',
          description: 'Tells a responder when the shift is over.',
          importance: Importance.max,
          sound: const RawResourceAndroidNotificationSound('iers_off_duty'),
          audioAttributesUsage: AudioAttributesUsage.alarm,
        ),
      );
      await android.createNotificationChannel(
        AndroidNotificationChannel(
          channelCrash,
          'Crash alerts',
          description: 'The phone saw a crash while the app was closed.',
          importance: Importance.max,
          sound: const RawResourceAndroidNotificationSound('iers_siren'),
          audioAttributesUsage: AudioAttributesUsage.alarm,
        ),
      );
    } catch (e) {
      debugPrint('[alerts] init failed: $e');
      _init = null; // try again next time
    }
  }

  // ───────────────────────────── duty ─────────────────────────────

  /// Words for the "OFF duty" notification. [reason] is MAX_DURATION or HEARTBEAT_LOST.
  static String offDutyText(String? reason) => reason == 'HEARTBEAT_LOST'
      ? 'We lost contact with your phone, so you were put OFF duty. Tap "Go on duty again" to get emergencies again.'
      : 'Your 1-hour shift is over. Tap "Go on duty again" to keep getting emergencies.';

  /// "You are now OFF duty": 5-second sound + button. The same notification id is used every time,
  /// and it only alerts once, so two places showing it (screen + service + push) make no double sound.
  Future<void> showOffDuty({String? reason, String? title, String? body}) async {
    if (!supported) return;
    await init();
    try {
      await _plugin.show(
        idOffDuty,
        (title == null || title.isEmpty) ? 'You are now OFF duty' : title,
        (body == null || body.isEmpty) ? offDutyText(reason) : body,
        NotificationDetails(
          android: AndroidNotificationDetails(
            channelDuty,
            'Duty alerts',
            channelDescription: 'Tells a responder when the shift is over.',
            importance: Importance.max,
            priority: Priority.high,
            category: AndroidNotificationCategory.alarm,
            sound: const RawResourceAndroidNotificationSound('iers_off_duty'),
            audioAttributesUsage: AudioAttributesUsage.alarm,
            enableVibration: true,
            onlyAlertOnce: true,
            autoCancel: false,
            actions: [
              AndroidNotificationAction(actionGoOnDuty, 'Go on duty again', showsUserInterface: false),
            ],
          ),
        ),
        payload: 'OFF_DUTY',
      );
    } catch (e) {
      debugPrint('[alerts] off-duty notification failed: $e');
    }
  }

  Future<void> cancelOffDuty() async {
    if (!supported) return;
    await init();
    try {
      await _plugin.cancel(idOffDuty);
    } catch (_) {}
  }

  // ───────────────────────────── crash while the app is closed ─────────────────────────────

  /// The phone saw a crash with the screen closed. [crashEventId] is null when the server could not
  /// be reached yet. The button cancels the help request (the server gives a 10-second window).
  /// [quiet] = only change the text of the notification that is already showing (no new siren).
  Future<void> showCrash(String? crashEventId, {String? text, bool quiet = false}) async {
    if (!supported) return;
    await init();
    try {
      await _plugin.show(
        idCrash,
        'Crash detected',
        text ?? 'Help is being requested. If you are OK, tap "I\'M OK" now.',
        NotificationDetails(
          android: AndroidNotificationDetails(
            channelCrash,
            'Crash alerts',
            channelDescription: 'The phone saw a crash while the app was closed.',
            importance: Importance.max,
            priority: Priority.max,
            category: AndroidNotificationCategory.alarm,
            sound: const RawResourceAndroidNotificationSound('iers_siren'),
            audioAttributesUsage: AudioAttributesUsage.alarm,
            enableVibration: true,
            fullScreenIntent: true, // wakes the screen and opens the app
            onlyAlertOnce: quiet,
            autoCancel: false,
            actions: crashEventId == null
                ? null
                : [AndroidNotificationAction(actionImOk, "I'M OK - cancel", showsUserInterface: false)],
          ),
        ),
        payload: crashEventId == null ? 'CRASH' : 'CRASH:$crashEventId',
      );
    } catch (e) {
      debugPrint('[alerts] crash notification failed: $e');
    }
  }

  Future<void> cancelCrash() async {
    if (!supported) return;
    await init();
    try {
      await _plugin.cancel(idCrash);
    } catch (_) {}
  }

  /// A plain message (result of a button, or a problem).
  Future<void> showInfo(String title, String body) async {
    if (!supported) return;
    await init();
    try {
      await _plugin.show(
        idInfo,
        title,
        body,
        NotificationDetails(
          android: AndroidNotificationDetails(
            channelInfo,
            'Updates',
            channelDescription: 'Help is on the way, request cancelled, and similar messages.',
            importance: Importance.high,
            priority: Priority.high,
          ),
        ),
      );
    } catch (_) {}
  }

  // ───────────────────────────── buttons ─────────────────────────────

  void _onForegroundResponse(NotificationResponse r) {
    final id = r.actionId;
    if (id != null && id.isNotEmpty) {
      runAction(id, r.payload).then((_) => onActionDone?.call(id));
    } else {
      onTap?.call(r.payload);
    }
  }

  /// What a notification button does. Needs no screen: it loads the server address and the login itself.
  static Future<void> runAction(String? actionId, String? payload) async {
    try {
      await AppConfig.I.load();
      await Session.I.load();
      if (!Session.I.isLoggedIn) return;
      switch (actionId) {
        case actionGoOnDuty:
          await _goOnDuty();
          break;
        case actionImOk:
          await _cancelCrash(payload);
          break;
      }
    } catch (e) {
      debugPrint('[alerts] button failed: $e');
    }
  }

  static Future<void> _goOnDuty() async {
    try {
      // Every ON_DUTY starts a new 1-hour shift on the server.
      await ApiClient.I.put('/api/responders/status', {'status': 'ON_DUTY'});
    } on ApiException catch (e) {
      await I.showInfo('Could not go on duty', e.isOffline ? 'No internet. Open the app and try again.' : e.message);
      return;
    }
    await I.cancelOffDuty();
    // The heartbeat must go on, otherwise the server puts the phone OFF duty again after 2 minutes.
    await BackgroundService.I.startResponder(fromBackground: true);
  }

  static Future<void> _cancelCrash(String? payload) async {
    final id = (payload != null && payload.startsWith('CRASH:')) ? payload.substring(6) : null;
    if (id == null || id.isEmpty) return;
    try {
      final r = await ApiClient.I.post('/api/telemetry/crash/$id/cancel');
      final status = r is Map ? r['status']?.toString() : null;
      if (status == 'TOO_LATE') {
        // The window was over: help was already asked for. Tell the unit to stand down.
        await ApiClient.I.post('/api/telemetry/crash/$id/late-cancel');
        await I.cancelCrash();
        await I.showInfo('Help cancelled', 'The rescue unit was told to stand down. Stay safe.');
      } else {
        await I.cancelCrash();
        await I.showInfo('Cancelled', 'No help was sent. Stay safe.');
      }
    } on ApiException catch (e) {
      await I.showInfo('Could not cancel', e.isOffline ? 'No internet. Open the app and cancel there.' : e.message);
    }
  }
}

/// Button presses on a notification while the app is not in front. MUST be top level.
@pragma('vm:entry-point')
void notificationTapBackground(NotificationResponse response) {
  DartPluginRegistrant.ensureInitialized();
  LocalAlerts.runAction(response.actionId, response.payload);
}
