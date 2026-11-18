import 'dart:convert';

import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Login tokens and who is logged in. Kept in secure storage.
class Session {
  Session._();
  static final Session I = Session._();

  static const _storage = FlutterSecureStorage();

  String? accessToken;
  String? refreshToken;
  String? userId;
  String? email;
  String? role;

  bool get isLoggedIn => (accessToken ?? '').isNotEmpty && userId != null;
  bool get isResponder => role == 'RESPONDER';
  bool get isDriver => role == 'DRIVER';

  /// When the access token stops working (from the JWT "exp"), or null if unknown.
  DateTime? get accessExpiry => _expiryOf(accessToken);

  bool get accessExpiresSoon {
    final exp = accessExpiry;
    return exp != null && exp.isBefore(DateTime.now().add(const Duration(seconds: 60)));
  }

  Future<void> load() async {
    accessToken = await _storage.read(key: 'access_token');
    refreshToken = await _storage.read(key: 'refresh_token');
    userId = await _storage.read(key: 'user_id');
    email = await _storage.read(key: 'email');
    role = await _storage.read(key: 'role');
  }

  /// Saves the "Tokens" reply of /auth/login, /auth/register and /auth/refresh.
  Future<void> saveTokens(Map<String, dynamic> reply) async {
    accessToken = reply['accessToken'] as String?;
    refreshToken = (reply['refreshToken'] as String?) ?? refreshToken;
    userId = (reply['userId'] ?? _claim(accessToken, 'sub') ?? userId)?.toString();
    email = (reply['email'] ?? _claim(accessToken, 'email') ?? email)?.toString();
    role = (reply['role'] ?? _claim(accessToken, 'role') ?? role)?.toString().toUpperCase();
    await _storage.write(key: 'access_token', value: accessToken);
    await _storage.write(key: 'refresh_token', value: refreshToken);
    await _storage.write(key: 'user_id', value: userId);
    await _storage.write(key: 'email', value: email);
    await _storage.write(key: 'role', value: role);
  }

  Future<void> clear() async {
    accessToken = refreshToken = userId = email = role = null;
    await _storage.deleteAll();
  }

  static Map<String, dynamic>? _claims(String? jwt) {
    if (jwt == null) return null;
    final parts = jwt.split('.');
    if (parts.length != 3) return null;
    try {
      final json = utf8.decode(base64Url.decode(base64Url.normalize(parts[1])));
      return jsonDecode(json) as Map<String, dynamic>;
    } catch (_) {
      return null;
    }
  }

  static Object? _claim(String? jwt, String name) => _claims(jwt)?[name];

  static DateTime? _expiryOf(String? jwt) {
    final exp = _claim(jwt, 'exp');
    if (exp is num) return DateTime.fromMillisecondsSinceEpoch(exp.toInt() * 1000);
    return null;
  }
}
