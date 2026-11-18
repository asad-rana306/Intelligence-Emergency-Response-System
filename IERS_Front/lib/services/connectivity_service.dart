import 'dart:async';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:flutter/foundation.dart';

/// Is the phone connected to any network? Others listen to [onBack] to resend what failed.
class ConnectivityService extends ChangeNotifier {
  ConnectivityService._();
  static final ConnectivityService I = ConnectivityService._();

  bool online = true;
  final _back = StreamController<void>.broadcast();
  Stream<void> get onBack => _back.stream;
  StreamSubscription? _sub;

  Future<void> start() async {
    try {
      online = _isOnline(await Connectivity().checkConnectivity());
    } catch (_) {}
    _sub ??= Connectivity().onConnectivityChanged.listen((r) {
      final now = _isOnline(r);
      if (now != online) {
        online = now;
        notifyListeners();
        if (now) _back.add(null);
      }
    });
  }

  static bool _isOnline(List<ConnectivityResult> r) => r.any((x) => x != ConnectivityResult.none);
}
