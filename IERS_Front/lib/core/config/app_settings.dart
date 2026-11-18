import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

enum CrashSensitivity { low, normal, high }

/// The user's safety choices. Saved on the phone.
class AppSettings extends ChangeNotifier {
  AppSettings._();
  static final AppSettings I = AppSettings._();

  SharedPreferences? _p;

  // ── driver ──
  /// Start an internet call to the responder automatically when they accept.
  bool get autoCallResponder => _p?.getBool('s.autoCall') ?? true;

  /// If the internet call cannot connect, dial the normal phone number.
  bool get phoneCallFallback => _p?.getBool('s.gsmFallback') ?? true;

  /// After a crash, if nobody touches the phone, it announces loudly for bystanders.
  bool get shoutForHelp => _p?.getBool('s.shout') ?? true;
  int get shoutDelaySeconds => _p?.getInt('s.shoutDelay') ?? 20;

  /// Language of the loud announcement: 'en' or 'ur'.
  String get announceLanguage => _p?.getString('s.lang') ?? 'en';

  /// Nobody accepted for 2 minutes → dial 1122 automatically.
  bool get autoDialRescue => _p?.getBool('s.auto1122') ?? false;
  CrashSensitivity get sensitivity => CrashSensitivity.values[(_p?.getInt('s.sensitivity') ?? 1).clamp(0, 2)];

  /// The paired car box id (null = no box, the phone can work as the box).
  String? get pairedDeviceId => _p?.getString('s.deviceId');

  // ── responder ──
  /// Answer the patient's call by itself on speaker (hands-free while driving).
  bool get autoAnswerCalls => _p?.getBool('s.autoAnswer') ?? true;
  String get dutyStatus => _p?.getString('s.duty') ?? 'OFF_DUTY';

  // ── network (optional TURN relay for very bad or blocked networks) ──
  String get turnUrl => _p?.getString('s.turnUrl') ?? '';
  String get turnUser => _p?.getString('s.turnUser') ?? '';
  String get turnPass => _p?.getString('s.turnPass') ?? '';

  // ── developer ──
  /// Shows "Simulate crash" and lets the phone detector work without speed (for testing at a desk).
  bool get devTools => _p?.getBool('s.dev') ?? false;

  Future<void> load() async {
    _p = await SharedPreferences.getInstance();
    await _p!.reload(); // the background service reads the same values; make sure they are fresh
  }

  Future<void> setBool(String key, bool v) async {
    await _p?.setBool(key, v);
    notifyListeners();
  }

  Future<void> setInt(String key, int v) async {
    await _p?.setInt(key, v);
    notifyListeners();
  }

  Future<void> setString(String key, String? v) async {
    if (v == null || v.isEmpty) {
      await _p?.remove(key);
    } else {
      await _p?.setString(key, v);
    }
    notifyListeners();
  }

  Future<void> setAutoCall(bool v) => setBool('s.autoCall', v);
  Future<void> setPhoneFallback(bool v) => setBool('s.gsmFallback', v);
  Future<void> setShout(bool v) => setBool('s.shout', v);
  Future<void> setShoutDelay(int v) => setInt('s.shoutDelay', v);
  Future<void> setLanguage(String v) => setString('s.lang', v);
  Future<void> setAutoDialRescue(bool v) => setBool('s.auto1122', v);
  Future<void> setSensitivity(CrashSensitivity v) => setInt('s.sensitivity', v.index);
  Future<void> setPairedDevice(String? id) => setString('s.deviceId', id);
  Future<void> setAutoAnswer(bool v) => setBool('s.autoAnswer', v);
  Future<void> setDuty(String v) => setString('s.duty', v);
  Future<void> setDevTools(bool v) => setBool('s.dev', v);
  Future<void> setTurn(String url, String user, String pass) async {
    await setString('s.turnUrl', url.trim());
    await setString('s.turnUser', user.trim());
    await setString('s.turnPass', pass);
  }
}
