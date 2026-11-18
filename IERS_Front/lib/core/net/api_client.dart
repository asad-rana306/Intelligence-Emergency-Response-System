import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:http_parser/http_parser.dart' show MediaType;

import '../config/app_config.dart';
import 'api_exception.dart';
import 'session.dart';

/// Every REST call goes through here: the address comes from [AppConfig], the login from [Session].
/// A 401 refreshes the token once and repeats the call. Errors are always [ApiException].
class ApiClient {
  ApiClient._();
  static final ApiClient I = ApiClient._();

  final http.Client _http = http.Client();
  Future<bool>? _refreshing;

  /// Called when the login cannot be renewed (the app goes back to the login screen).
  VoidCallback? onSessionExpired;

  Future<dynamic> get(String path, {Map<String, String>? query}) => _send('GET', path, query: query);
  Future<dynamic> post(String path, [Object? body]) => _send('POST', path, body: body);
  Future<dynamic> put(String path, [Object? body]) => _send('PUT', path, body: body);
  Future<dynamic> delete(String path, {Map<String, String>? query}) => _send('DELETE', path, query: query);

  /// Calls that need no login (login, register, refresh, health).
  Future<dynamic> postOpen(String path, Object body) => _send('POST', path, body: body, auth: false);

  /// Is the gateway reachable at this address? (Used by "Test connection".)
  Future<String> ping({String? base}) async {
    final uri = Uri.parse('${base ?? AppConfig.I.httpBase}/actuator/health');
    try {
      final res = await _http.get(uri).timeout(const Duration(seconds: 6));
      if (res.statusCode == 200) return 'Connected. The server answered.';
      return 'The server answered with ${res.statusCode}. Address is right, a service may be down.';
    } on TimeoutException {
      throw const ApiException(0, 'No answer in 6 seconds. Check the IP and that the phone is on the same network.');
    } on SocketException catch (e) {
      throw ApiException(0, 'Cannot reach $uri (${e.osError?.message ?? e.message})');
    } catch (e) {
      throw ApiException(0, 'Cannot reach the server: $e');
    }
  }

  /// Multipart upload (voice notes). [fields] are plain form fields.
  Future<dynamic> upload(
    String path, {
    required File file,
    required String fileField,
    Map<String, String> fields = const {},
    String contentType = 'audio/mp4',
  }) async {
    Future<http.Response> attempt() async {
      final req = http.MultipartRequest('POST', AppConfig.I.api(path));
      req.headers.addAll(_headers(json: false));
      req.fields.addAll(fields);
      final parts = contentType.split('/');
      req.files.add(
        await http.MultipartFile.fromPath(
          fileField,
          file.path,
          contentType: MediaType(parts.first, parts.length > 1 ? parts[1] : 'octet-stream'),
        ),
      );
      final streamed = await _http.send(req).timeout(AppConfig.uploadTimeout);
      return http.Response.fromStream(streamed);
    }

    return _guard(() async {
      var res = await attempt();
      if (res.statusCode == 401 && await _refresh()) res = await attempt();
      return _decode(res);
    });
  }

  /// Multipart upload from bytes — works on web and mobile (no file path needed).
  Future<dynamic> uploadBytes(
    String path, {
    required List<int> bytes,
    required String fileField,
    String filename = 'voice',
    Map<String, String> fields = const {},
    String contentType = 'audio/mp4',
  }) async {
    Future<http.Response> attempt() async {
      final req = http.MultipartRequest('POST', AppConfig.I.api(path));
      req.headers.addAll(_headers(json: false));
      req.fields.addAll(fields);
      final parts = contentType.split('/');
      req.files.add(
        http.MultipartFile.fromBytes(
          fileField,
          bytes,
          filename: filename,
          contentType: MediaType(parts.first, parts.length > 1 ? parts[1] : 'octet-stream'),
        ),
      );
      final streamed = await _http.send(req).timeout(AppConfig.uploadTimeout);
      return http.Response.fromStream(streamed);
    }

    return _guard(() async {
      var res = await attempt();
      if (res.statusCode == 401 && await _refresh()) res = await attempt();
      return _decode(res);
    });
  }

  /// Downloads bytes with the login (voice notes are protected).
  Future<List<int>> download(String pathOrUrl) async {
    return _guard(() async {
      Future<http.Response> attempt() =>
          _http.get(AppConfig.I.resolve(pathOrUrl), headers: _headers(json: false)).timeout(AppConfig.uploadTimeout);
      var res = await attempt();
      if (res.statusCode == 401 && await _refresh()) res = await attempt();
      if (res.statusCode >= 200 && res.statusCode < 300) return res.bodyBytes;
      throw ApiException(res.statusCode, _errorText(res));
    });
  }

  /// Makes sure the access token is fresh (sockets call this before connecting).
  Future<void> ensureFreshToken() async {
    if (Session.I.isLoggedIn && Session.I.accessExpiresSoon) await _refresh();
  }

  // ───────────────────────────── internals ─────────────────────────────

  Future<dynamic> _send(String method, String path, {Object? body, Map<String, String>? query, bool auth = true}) {
    return _guard(() async {
      Future<http.Response> attempt() {
        final uri = AppConfig.I.api(path, query);
        final headers = _headers(auth: auth);
        final encoded = body == null ? null : jsonEncode(body);
        if (kDebugMode) debugPrint('[API] $method $uri');
        switch (method) {
          case 'GET':
            return _http.get(uri, headers: headers).timeout(AppConfig.requestTimeout);
          case 'POST':
            return _http.post(uri, headers: headers, body: encoded).timeout(AppConfig.requestTimeout);
          case 'PUT':
            return _http.put(uri, headers: headers, body: encoded).timeout(AppConfig.requestTimeout);
          case 'DELETE':
            return _http.delete(uri, headers: headers).timeout(AppConfig.requestTimeout);
        }
        throw ApiException(0, 'Unknown method $method');
      }

      var res = await attempt();
      if (auth && res.statusCode == 401 && await _refresh()) {
        res = await attempt();
      }
      return _decode(res);
    });
  }

  Future<T> _guard<T>(Future<T> Function() call) async {
    try {
      return await call();
    } on ApiException {
      rethrow;
    } on TimeoutException {
      throw const ApiException(0, 'The server did not answer in time. Weak internet?');
    } on SocketException {
      throw const ApiException(0, 'No internet connection, or the server address is wrong.');
    } on http.ClientException catch (e) {
      throw ApiException(0, 'Connection problem: ${e.message}');
    } on HandshakeException {
      throw const ApiException(0, 'Secure connection failed (https). Check the server settings.');
    }
  }

  Map<String, String> _headers({bool auth = true, bool json = true}) {
    final h = <String, String>{'Accept': 'application/json'};
    if (json) h['Content-Type'] = 'application/json';
    final token = Session.I.accessToken;
    if (auth && token != null && token.isNotEmpty) h['Authorization'] = 'Bearer $token';
    return h;
  }

  /// One refresh at a time; everyone waiting shares the result.
  Future<bool> _refresh() {
    return _refreshing ??= _doRefresh().whenComplete(() => _refreshing = null);
  }

  Future<bool> _doRefresh() async {
    // The app has more than one isolate (the screen and the background service) and the server
    // changes the refresh token on every refresh. So first look at the stored login: if the
    // other isolate already refreshed, use its tokens instead of spending the old refresh token.
    final before = Session.I.accessToken;
    try {
      await Session.I.load();
    } catch (_) {}
    if (Session.I.isLoggedIn && Session.I.accessToken != before && !Session.I.accessExpiresSoon) return true;

    final rt = Session.I.refreshToken;
    if (rt == null || rt.isEmpty) {
      _expired();
      return false;
    }
    try {
      final res = await _http
          .post(
            AppConfig.I.api('/auth/refresh'),
            headers: {'Content-Type': 'application/json', 'Accept': 'application/json'},
            body: jsonEncode({'refreshToken': rt}),
          )
          .timeout(AppConfig.requestTimeout);
      if (res.statusCode == 200) {
        await Session.I.saveTokens(jsonDecode(res.body) as Map<String, dynamic>);
        return true;
      }
      if (res.statusCode == 401 || res.statusCode == 403) _expired();
    } catch (_) {
      // No network: keep the session, the next call tries again.
    }
    return false;
  }

  void _expired() {
    final cb = onSessionExpired;
    if (cb != null) scheduleMicrotask(cb);
  }

  dynamic _decode(http.Response res) {
    if (res.statusCode == 204 || res.body.isEmpty) {
      if (res.statusCode >= 200 && res.statusCode < 300) return null;
      throw ApiException(res.statusCode, _fallbackText(res.statusCode));
    }
    if (res.statusCode >= 200 && res.statusCode < 300) {
      try {
        return jsonDecode(utf8.decode(res.bodyBytes));
      } catch (_) {
        return res.body;
      }
    }
    throw ApiException(
      res.statusCode,
      _errorText(res),
      retryAfter: int.tryParse(res.headers['retry-after'] ?? ''),
    );
  }

  String _errorText(http.Response res) {
    try {
      final body = jsonDecode(utf8.decode(res.bodyBytes));
      if (body is Map) {
        final m = body['message'] ?? body['error'];
        if (m != null && m.toString().isNotEmpty) return m.toString();
      }
    } catch (_) {}
    return _fallbackText(res.statusCode);
  }

  String _fallbackText(int status) {
    switch (status) {
      case 400:
        return 'The server did not accept this request.';
      case 401:
        return 'Please log in again.';
      case 403:
        return 'You are not allowed to do this.';
      case 404:
        return 'Not found.';
      case 409:
        return 'This changed in the meantime. Refresh and try again.';
      case 429:
        return 'Too many tries. Please wait a moment and try again.';
      case 502:
      case 503:
        return 'A server service is not running. Try again in a moment.';
      default:
        return 'Server error ($status).';
    }
  }
}
