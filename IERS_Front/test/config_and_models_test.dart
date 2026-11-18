import 'package:flutter_test/flutter_test.dart';
import 'package:iers_app/core/config/app_config.dart';
import 'package:iers_app/core/utils/geo.dart';
import 'package:iers_app/models/models.dart';

void main() {
  group('AppConfig.parse (the one server address)', () {
    test('plain IP uses the default port', () {
      final p = AppConfig.parse('192.168.1.20')!;
      expect(p.host, '192.168.1.20');
      expect(p.port, 8080);
      expect(p.https, isFalse);
    });

    test('IP with port', () {
      final p = AppConfig.parse('10.0.2.2:9090')!;
      expect(p.host, '10.0.2.2');
      expect(p.port, 9090);
    });

    test('full URL with https and path', () {
      final p = AppConfig.parse('https://iers.example.com:8443/api')!;
      expect(p.host, 'iers.example.com');
      expect(p.port, 8443);
      expect(p.https, isTrue);
    });

    test('garbage is refused', () {
      expect(AppConfig.parse(''), isNull);
      expect(AppConfig.parse('my server'), isNull);
      expect(AppConfig.parse('1.2.3.4:99999'), isNull);
    });

    test('every address comes from the one host', () {
      final c = AppConfig.I
        ..host = '192.168.0.7'
        ..port = 8080
        ..https = false;
      expect(c.api('/auth/login').toString(), 'http://192.168.0.7:8080/auth/login');
      expect(c.liveSocketUrl, 'ws://192.168.0.7:8080/ws/live/websocket');
      expect(c.gpsSocketUrl, 'ws://192.168.0.7:8080/ws/incident/websocket');
      expect(c.resolve('/api/incidents/x/messages/y/audio').host, '192.168.0.7');
    });
  });

  group('models follow the contract', () {
    test('live event', () {
      final e = LiveEvent.fromJson({
        'type': 'INCIDENT_OFFERED',
        'eventId': 'e1',
        'serverTime': '2026-10-31T10:00:00Z',
        'data': {'incidentId': 'i1', 'gpsLat': 31.5, 'gpsLng': 74.3, 'priorityScore': 5},
      });
      expect(e.type, 'INCIDENT_OFFERED');
      expect(e.incidentId, 'i1');
      final o = Offer.fromJson(e.data);
      expect(o.lat, 31.5);
      expect(Priority.label(o.priorityScore), 'CRITICAL');
    });

    test('chat message with voice', () {
      final m = ChatMessage.fromJson({
        'messageId': 'm1',
        'incidentId': 'i1',
        'senderId': 'u1',
        'senderRole': 'DRIVER',
        'kind': 'VOICE',
        'durationMs': 4200,
        'clientMessageId': 'c1',
        'createdAt': '2026-10-31T10:00:00Z',
        'audioUrl': '/api/incidents/i1/messages/m1/audio',
      });
      expect(m.isVoice, isTrue);
      expect(m.durationMs, 4200);
      expect(m.clientMessageId, 'c1');
    });

    test('history row', () {
      final h = HistoryItem.fromJson({
        'incidentId': 'i1',
        'status': 'DISPATCHING',
        'myResponse': 'REJECTED',
        'canAcceptAgain': true,
      });
      expect(h.canAcceptAgain, isTrue);
    });
  });

  test('distance text', () {
    expect(Geo.distanceText(850), '850 m');
    expect(Geo.distanceText(3200), '3.2 km');
    expect(Geo.meters(31.5204, 74.3587, 31.5204, 74.3587), 0);
  });
}
