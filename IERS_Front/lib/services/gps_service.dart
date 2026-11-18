import 'dart:async';

import '../core/config/app_config.dart';
import '../core/net/session.dart';
import '../core/net/stomp_socket.dart';

/// Live GPS during a mission (Dispatch service, /ws/incident).
/// The responder sends to /app/gps/{incidentId}; the driver listens on /topic/gps/{incidentId}.
class GpsService {
  GpsService._() {
    socket = StompSocket('gps', () {
      final token = Uri.encodeQueryComponent(Session.I.accessToken ?? '');
      return '${AppConfig.I.gpsSocketUrl}?token=$token';
    });
  }
  static final GpsService I = GpsService._();

  late final StompSocket socket;
  String? _watching;

  /// Driver: listen to the responder's position.
  Future<void> watch(String incidentId, void Function(double lat, double lng, double? etaMinutes) onPosition) async {
    if (_watching != null && _watching != incidentId) socket.unlisten('/topic/gps/$_watching');
    _watching = incidentId;
    socket.unlisten('/topic/gps/$incidentId');
    socket.listen('/topic/gps/$incidentId', (m) {
      final lat = (m['latitude'] as num?)?.toDouble();
      final lng = (m['longitude'] as num?)?.toDouble();
      if (lat == null || lng == null) return;
      onPosition(lat, lng, (m['estimatedEta'] as num?)?.toDouble());
    });
    await socket.start();
  }

  /// Responder: send my position. Returns false when the socket is down (the next one will go).
  bool send(
    String incidentId,
    String responderId,
    double lat,
    double lng, {
    double? heading,
    double? speed,
    double? etaMinutes,
  }) {
    return socket.send('/app/gps/$incidentId', {
      'incidentId': incidentId,
      'responderId': responderId,
      'latitude': lat,
      'longitude': lng,
      'heading': heading,
      'speed': speed,
      'estimatedEta': etaMinutes,
    });
  }

  Future<void> startSending() => socket.start();

  void stop() {
    if (_watching != null) socket.unlisten('/topic/gps/$_watching');
    _watching = null;
    socket.stop();
  }
}
