import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../core/config/app_settings.dart';
import '../core/net/api_client.dart';
import '../core/net/api_exception.dart';
import '../models/models.dart';
import 'auth_service.dart';

/// The driver's medical info, emergency contacts and car box.
/// A copy is kept on the phone, so the Medical ID and the contacts work without internet.
class ProfileService extends ChangeNotifier {
  ProfileService._();
  static final ProfileService I = ProfileService._();

  MedicalProfile medical = const MedicalProfile();
  bool medicalOnServer = false;
  List<EmergencyContact> contacts = [];
  String? deviceLastPing;

  Future<void> loadCached() async {
    final p = await SharedPreferences.getInstance();
    try {
      final m = p.getString('cache.medical');
      if (m != null) medical = MedicalProfile.fromJson(jsonDecode(m) as Map<String, dynamic>);
      final c = p.getString('cache.contacts');
      if (c != null) {
        contacts = (jsonDecode(c) as List)
            .map((j) => EmergencyContact.fromJson(Map<String, dynamic>.from(j as Map)))
            .toList();
      }
    } catch (_) {}
  }

  Future<void> refresh() async {
    await Future.wait([_loadMedical(), _loadContacts()]);
    notifyListeners();
  }

  Future<void> _loadMedical() async {
    try {
      final r = await ApiClient.I.get('/api/medical-profiles/me');
      if (r is Map) {
        medical = MedicalProfile.fromJson(Map<String, dynamic>.from(r));
        medicalOnServer = true;
        await _cache('cache.medical', medical.toJson());
      }
    } on ApiException catch (e) {
      if (e.isNotFound) medicalOnServer = false;
    }
  }

  Future<void> _loadContacts() async {
    try {
      final r = await ApiClient.I.get('/api/emergency-contacts/me');
      if (r is List) {
        contacts = r.map((j) => EmergencyContact.fromJson(Map<String, dynamic>.from(j as Map))).toList();
        await _cache(
          'cache.contacts',
          contacts
              .map((c) => {'id': c.id, 'contactName': c.name, 'phone': c.phone, 'relationship': c.relationship})
              .toList(),
        );
      }
    } on ApiException catch (_) {}
  }

  Future<void> saveMedical(MedicalProfile m) async {
    if (medicalOnServer) {
      try {
        await ApiClient.I.put('/api/medical-profiles/me', m.toJson());
      } on ApiException catch (e) {
        if (!e.isNotFound) rethrow;
        await ApiClient.I.post('/api/medical-profiles', m.toJson());
      }
    } else {
      try {
        await ApiClient.I.post('/api/medical-profiles', m.toJson());
      } on ApiException catch (e) {
        if (!e.isConflict && e.status != 400) rethrow;
        await ApiClient.I.put('/api/medical-profiles/me', m.toJson());
      }
    }
    medical = m;
    medicalOnServer = true;
    await _cache('cache.medical', m.toJson());
    notifyListeners();
  }

  Future<void> addContact(String name, String phone, String relationship) async {
    await ApiClient.I.post('/api/emergency-contacts', {
      'contactName': name,
      'phone': phone,
      'relationship': relationship,
    });
    await _loadContacts();
    notifyListeners();
  }

  Future<void> deleteContact(EmergencyContact c) async {
    if (c.id != null) await ApiClient.I.delete('/api/emergency-contacts/${c.id}');
    contacts.removeWhere((x) => x.id == c.id);
    notifyListeners();
    await _loadContacts();
    notifyListeners();
  }

  /// Pair the car box with this driver.
  Future<void> pairDevice(String deviceId) async {
    final a = AuthService.I;
    await ApiClient.I.post('/api/devices/pair', {
      'deviceId': deviceId.trim(),
      'driverName': a.fullName ?? a.email ?? 'Driver',
      'driverPhone': a.phone ?? '',
    });
    await AppSettings.I.setPairedDevice(deviceId.trim());
    await deviceStatus();
  }

  Future<void> unpairLocal() async {
    await AppSettings.I.setPairedDevice(null);
    deviceLastPing = null;
    notifyListeners();
  }

  /// Last heartbeat of the car box, as text (or null).
  Future<void> deviceStatus() async {
    final id = AppSettings.I.pairedDeviceId;
    if (id == null) return;
    try {
      final r = await ApiClient.I.get('/api/devices/$id/status');
      if (r is Map) {
        final hb = r['lastHeartbeat'];
        deviceLastPing = hb?.toString();
      }
    } catch (_) {}
    notifyListeners();
  }

  Future<void> _cache(String key, Object value) async {
    final p = await SharedPreferences.getInstance();
    await p.setString(key, jsonEncode(value));
  }
}
