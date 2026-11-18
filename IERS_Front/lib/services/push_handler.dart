import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';

import '../controllers/driver_controller.dart';
import '../controllers/responder_controller.dart';
import 'alarm_service.dart';
import 'duty_service.dart';

/// What the app does with a push message while it is open, or when the user taps a notification
/// that opened it. (When the app is closed, see `onBackgroundMessage` in push_service.dart.)
///
/// `data.type` is set by the Dispatch service:
///   OFFER       a rescue request (responder)          → fetch it; the offer screen opens and rings
///   ACCEPTED    a responder is on the way (driver)    → beep and refresh
///   STAND_DOWN  the driver cancelled (responder)      → refresh
///   OFF_DUTY    the shift is over / phone was silent  → the OFF-duty alert and dialog
Future<void> handlePushMessage(RemoteMessage msg) async {
  final data = msg.data;
  final type = data['type'] ?? '';
  debugPrint('[push] $type ${msg.messageId}');

  switch (type) {
    case 'OFFER':
      // The ring starts inside restore() when there really is an open offer.
      try {
        await ResponderController.I.restore();
      } catch (_) {}
      break;

    case 'ACCEPTED':
      try {
        AlarmService.I.beep();
      } catch (_) {}
      try {
        await DriverController.I.restore();
      } catch (_) {}
      break;

    case 'STAND_DOWN':
      try {
        await ResponderController.I.restore();
      } catch (_) {}
      break;

    case 'OFF_DUTY':
      DutyService.I.serverPutOffDuty(data['reason'], title: data['title'], body: data['body']);
      break;
  }
}
