import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:flutter/foundation.dart';
import 'package:stomp_dart_client/stomp_dart_client.dart';

import 'api_client.dart';

enum SocketState { off, connecting, online, offline }

/// A STOMP WebSocket that keeps itself alive on a bad network:
///  - reconnects with back-off (1, 2, 4 … 15 s) and a FRESH login token every time,
///  - re-subscribes everything after a reconnect,
///  - heart-beats every 10 s so a dead mobile connection is noticed fast.
class StompSocket {
  StompSocket(this.name, this._urlBuilder);

  final String name;
  final String Function() _urlBuilder;

  final ValueNotifier<SocketState> state = ValueNotifier(SocketState.off);
  VoidCallback? onConnected;

  final Map<String, List<void Function(Map<String, dynamic>)>> _handlers = {};
  final Map<String, StompUnsubscribe> _active = {};
  StompClient? _client;
  bool _wanted = false;
  Timer? _retry;
  int _attempt = 0;

  bool get isOnline => state.value == SocketState.online;

  Future<void> start() async {
    if (_wanted) return;
    _wanted = true;
    await _open();
  }

  void stop() {
    _wanted = false;
    _retry?.cancel();
    _active.clear();
    final c = _client;
    _client = null;
    try {
      c?.deactivate();
    } catch (_) {}
    state.value = SocketState.off;
  }

  /// New server address or new login: close and open again.
  Future<void> restart() async {
    stop();
    await start();
  }

  /// Listen to a destination. Survives reconnects.
  void listen(String destination, void Function(Map<String, dynamic>) handler) {
    _handlers.putIfAbsent(destination, () => []).add(handler);
    if (isOnline && !_active.containsKey(destination)) _subscribe(destination);
  }

  void unlisten(String destination) {
    _handlers.remove(destination);
    final un = _active.remove(destination);
    try {
      un?.call();
    } catch (_) {}
  }

  /// Sends JSON. Returns false when offline (the caller decides what to do).
  bool send(String destination, [Map<String, dynamic>? body]) {
    final c = _client;
    if (c == null || !c.connected) return false;
    c.send(
      destination: destination,
      body: jsonEncode(body ?? const {}),
      headers: const {'content-type': 'application/json'},
    );
    return true;
  }

  // ───────────────────────────── internals ─────────────────────────────

  Future<void> _open() async {
    if (!_wanted) return;
    state.value = SocketState.connecting;
    try {
      await ApiClient.I.ensureFreshToken();
    } catch (_) {}
    if (!_wanted) return;

    late final StompClient client;
    client = StompClient(
      config: StompConfig(
        url: _urlBuilder(),
        onConnect: (_) => _connected(client),
        onWebSocketError: (dynamic e) => _lost(client, 'socket error: $e'),
        onStompError: (StompFrame f) => _lost(client, 'stomp error: ${f.body ?? f.headers}'),
        onDisconnect: (_) => _lost(client, 'disconnected'),
        onWebSocketDone: () => _lost(client, 'closed'),
        reconnectDelay: Duration.zero, // we reconnect ourselves, with a fresh token
        heartbeatIncoming: const Duration(seconds: 10),
        heartbeatOutgoing: const Duration(seconds: 10),
        connectionTimeout: const Duration(seconds: 10),
      ),
    );
    _client = client;
    client.activate();
  }

  void _connected(StompClient c) {
    if (!identical(c, _client)) return;
    _attempt = 0;
    _active.clear();
    state.value = SocketState.online;
    if (kDebugMode) debugPrint('[$name] online');
    for (final d in _handlers.keys) {
      _subscribe(d);
    }
    onConnected?.call();
  }

  void _lost(StompClient c, String why) {
    if (!identical(c, _client)) return;
    if (kDebugMode) debugPrint('[$name] lost: $why');
    _client = null;
    _active.clear();
    try {
      c.deactivate();
    } catch (_) {}
    if (!_wanted) return;
    state.value = SocketState.offline;
    _retry?.cancel();
    final seconds = min(15, pow(2, _attempt).toInt());
    _attempt = min(_attempt + 1, 6);
    _retry = Timer(Duration(seconds: seconds), _open);
  }

  void _subscribe(String destination) {
    final c = _client;
    if (c == null) return;
    _active[destination] = c.subscribe(
      destination: destination,
      callback: (StompFrame frame) {
        final body = frame.body;
        if (body == null || body.isEmpty) return;
        Map<String, dynamic> json;
        try {
          final decoded = jsonDecode(body);
          if (decoded is! Map<String, dynamic>) return;
          json = decoded;
        } catch (_) {
          return;
        }
        for (final h in List.of(_handlers[destination] ?? const [])) {
          try {
            h(json);
          } catch (e) {
            if (kDebugMode) debugPrint('[$name] handler error on $destination: $e');
          }
        }
      },
    );
  }
}
