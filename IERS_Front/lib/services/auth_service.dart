import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/net/api_client.dart';
import '../core/net/session.dart';
import 'push_service.dart';

/// Who is logged in, plus login / register / logout.
class AuthService extends ChangeNotifier {
  AuthService._();
  static final AuthService I = AuthService._();

  String? fullName;
  String? phone;
  String? vehicleId;

  bool get isLoggedIn => Session.I.isLoggedIn;
  bool get isResponder => Session.I.isResponder;
  String? get userId => Session.I.userId;
  String? get email => Session.I.email;
  String get firstName => (fullName ?? '').trim().split(' ').first;

  Future<void> load() async {
    await Session.I.load();
    final p = await SharedPreferences.getInstance();
    fullName = p.getString('me.name');
    phone = p.getString('me.phone');
    vehicleId = p.getString('me.vehicle');
  }

  Future<void> login(String email, String password) async {
    final reply = await ApiClient.I.postOpen('/auth/login', {'email': email.trim(), 'password': password});
    await Session.I.saveTokens(Map<String, dynamic>.from(reply as Map));
    await refreshMe();
    notifyListeners();
  }

  Future<void> register({
    required String fullName,
    required String email,
    required String phone,
    required String password,
    required bool responder,
    String? vehicleId,
  }) async {
    final body = <String, dynamic>{
      'fullName': fullName.trim(),
      'email': email.trim(),
      'phone': phone.trim(),
      'password': password,
      'role': responder ? 'RESPONDER' : 'DRIVER',
      if (responder && (vehicleId ?? '').trim().isNotEmpty) 'vehicleId': vehicleId!.trim(),
    };
    final reply = await ApiClient.I.postOpen('/auth/register', body);
    await Session.I.saveTokens(Map<String, dynamic>.from(reply as Map));
    this.vehicleId = vehicleId?.trim();
    await _saveLocal(fullName.trim(), phone.trim());
    await refreshMe();
    notifyListeners();
  }

  /// GET /api/users/me. Keeps the last good values when offline.
  Future<void> refreshMe() async {
    try {
      final me = await ApiClient.I.get('/api/users/me');
      if (me is Map) {
        await _saveLocal(me['fullName']?.toString() ?? fullName, me['phone']?.toString() ?? phone);
      }
    } catch (_) {}
  }

  Future<void> logout() async {
    // While the login still works: stop pushes to this phone for this user.
    await PushService.I.unregisterBeforeLogout();
    try {
      await ApiClient.I.post('/auth/logout');
    } catch (_) {}
    await Session.I.clear();
    final p = await SharedPreferences.getInstance();
    await p.remove('me.name');
    await p.remove('me.phone');
    await p.remove('me.vehicle');
    fullName = phone = vehicleId = null;
    notifyListeners();
  }

  /// The server said the login is no longer valid.
  Future<void> sessionExpired() async {
    await Session.I.clear();
    notifyListeners();
  }

  Future<void> _saveLocal(String? name, String? phoneNo) async {
    fullName = name;
    phone = phoneNo;
    final p = await SharedPreferences.getInstance();
    if (name != null) await p.setString('me.name', name);
    if (phoneNo != null) await p.setString('me.phone', phoneNo);
    if (vehicleId != null) await p.setString('me.vehicle', vehicleId!);
  }
}
