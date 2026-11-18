import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// THE ONE PLACE for the server address.
///
/// Every REST call and both WebSockets are built from [host] + [port] + [https].
/// Change the server once (Settings → Server, or the gear on the login screen) and the
/// whole app follows. A build can also set the default:
///   flutter run --dart-define=IERS_HOST=192.168.1.20 --dart-define=IERS_PORT=8080
class AppConfig extends ChangeNotifier {
  AppConfig._();
  static final AppConfig I = AppConfig._();

  static const String defaultHost = String.fromEnvironment('IERS_HOST', defaultValue: '16.4.21.178');
  static const int defaultPort = int.fromEnvironment('IERS_PORT', defaultValue: 8080);
  static const bool defaultHttps = bool.fromEnvironment('IERS_HTTPS', defaultValue: false);

  // Fixed numbers from the API contract.
  static const Duration cancelWindow = Duration(seconds: 10);
  static const Duration requestTimeout = Duration(seconds: 12);
  static const Duration uploadTimeout = Duration(seconds: 45);

  static const _kHost = 'server.host';
  static const _kPort = 'server.port';
  static const _kHttps = 'server.https';

  String host = defaultHost;
  int port = defaultPort;
  bool https = defaultHttps;

  String get httpBase => '${https ? 'https' : 'http'}://$host:$port';
  String get wsBase => '${https ? 'wss' : 'ws'}://$host:$port';

  /// Realtime service: events + call set-up (raw STOMP WebSocket through the gateway).
  String get liveSocketUrl => '$wsBase/ws/live/websocket';

  /// Dispatch service: live GPS of the responder during a mission.
  String get gpsSocketUrl => '$wsBase/ws/incident/websocket';

  /// Short text for the screen, e.g. "192.168.1.20:8080".
  String get label => '$host:$port${https ? ' (https)' : ''}';

  Uri api(String path, [Map<String, String>? query]) {
    final uri = Uri.parse('$httpBase$path');
    return query == null ? uri : uri.replace(queryParameters: query);
  }

  /// A path the server gave us (e.g. a voice note's audioUrl) → a full address.
  Uri resolve(String pathOrUrl) => pathOrUrl.startsWith('http') ? Uri.parse(pathOrUrl) : api(pathOrUrl);

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    await p.reload(); // the background service reads the same values; make sure they are fresh
    host = p.getString(_kHost) ?? defaultHost;
    port = p.getInt(_kPort) ?? defaultPort;
    https = p.getBool(_kHttps) ?? defaultHttps;
  }

  Future<void> save({required String host, required int port, required bool https}) async {
    this.host = host.trim();
    this.port = port;
    this.https = https;
    final p = await SharedPreferences.getInstance();
    await p.setString(_kHost, this.host);
    await p.setInt(_kPort, port);
    await p.setBool(_kHttps, https);
    notifyListeners();
  }

  /// Accepts "192.168.1.20", "192.168.1.20:8080", "http://my-server.com:8080/".
  /// Returns null when the text is not an address.
  static ({String host, int port, bool https})? parse(String text, {int fallbackPort = 8080}) {
    var t = text.trim();
    if (t.isEmpty) return null;
    var https = false;
    if (t.startsWith('https://')) {
      https = true;
      t = t.substring(8);
    } else if (t.startsWith('http://')) {
      t = t.substring(7);
    }
    t = t.split('/').first;
    var port = fallbackPort;
    final colon = t.lastIndexOf(':');
    if (colon > 0) {
      final p = int.tryParse(t.substring(colon + 1));
      if (p == null || p <= 0 || p > 65535) return null;
      port = p;
      t = t.substring(0, colon);
    }
    if (t.isEmpty || t.contains(' ')) return null;
    return (host: t, port: port, https: https);
  }
}
