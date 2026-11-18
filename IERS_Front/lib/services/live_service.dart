import 'dart:async';

import 'package:flutter/foundation.dart';

import '../core/config/app_config.dart';
import '../core/net/session.dart';
import '../core/net/stomp_socket.dart';
import '../models/models.dart';

/// The phone's private live channel (Realtime service).
///   events: CRASH_PENDING, INCIDENT_OFFERED, CHAT_MESSAGE, SNAPSHOT, CALL_ERROR …
///   calls:  RING / ACCEPT / DECLINE / OFFER / ANSWER / ICE / HANGUP
/// A repeat of the same event (same eventId) is dropped.
class LiveService {
  LiveService._() {
    socket = StompSocket('live', () {
      final token = Uri.encodeQueryComponent(Session.I.accessToken ?? '');
      return '${AppConfig.I.liveSocketUrl}?token=$token';
    });
    socket.listen('/user/queue/events', _onEvent);
    socket.listen('/user/queue/call', _onCall);
  }
  static final LiveService I = LiveService._();

  late final StompSocket socket;
  final _events = StreamController<LiveEvent>.broadcast();
  final _calls = StreamController<CallSignal>.broadcast();
  final _seen = <String>{}; // insertion-ordered

  Stream<LiveEvent> get events => _events.stream;
  Stream<CallSignal> get calls => _calls.stream;
  ValueListenable<SocketState> get state => socket.state;

  Future<void> start() => socket.start();
  void stop() => socket.stop();
  Future<void> restart() => socket.restart();

  /// Ask the server for a fresh SNAPSHOT (after coming back from the background).
  void requestSnapshot() => socket.send('/app/snapshot');

  /// Returns false when offline.
  bool sendCall(String incidentId, String kind, [Map<String, dynamic>? payload]) =>
      socket.send('/app/call/$incidentId', {'incidentId': incidentId, 'kind': kind, 'payload': payload ?? {}});

  void _onEvent(Map<String, dynamic> json) {
    final e = LiveEvent.fromJson(json);
    final id = e.eventId;
    if (id != null) {
      if (_seen.contains(id)) return;
      _seen.add(id);
      if (_seen.length > 300) _seen.remove(_seen.first);
    }
    if (kDebugMode) debugPrint('[live] ${e.type} ${e.data}');
    _events.add(e);
  }

  void _onCall(Map<String, dynamic> json) => _calls.add(CallSignal.fromJson(json));
}
