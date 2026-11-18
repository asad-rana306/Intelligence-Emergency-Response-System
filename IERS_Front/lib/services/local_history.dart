import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// A simple diary of what happened on this phone (crash detected, cancelled, help sent, …).
/// The driver's "History" screen reads it. Max 150 entries.
class HistoryEntry {
  final DateTime at;
  final String title;
  final String? detail;
  final String kind; // CRASH, CANCEL, HELP, DONE, SOS, INFO
  final String? incidentId;
  HistoryEntry(this.at, this.title, this.kind, {this.detail, this.incidentId});

  Map<String, dynamic> toJson() => {
    'at': at.toIso8601String(),
    'title': title,
    'detail': detail,
    'kind': kind,
    'incidentId': incidentId,
  };

  factory HistoryEntry.fromJson(Map<String, dynamic> j) => HistoryEntry(
    DateTime.tryParse(j['at'] as String? ?? '') ?? DateTime.now(),
    j['title'] as String? ?? '',
    j['kind'] as String? ?? 'INFO',
    detail: j['detail'] as String?,
    incidentId: j['incidentId'] as String?,
  );
}

class LocalHistory extends ChangeNotifier {
  LocalHistory._();
  static final LocalHistory I = LocalHistory._();

  static const _key = 'history.entries';
  List<HistoryEntry> entries = [];

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    try {
      entries = ((jsonDecode(p.getString(_key) ?? '[]')) as List)
          .map((j) => HistoryEntry.fromJson(Map<String, dynamic>.from(j as Map)))
          .toList();
    } catch (_) {
      entries = [];
    }
  }

  Future<void> add(String title, String kind, {String? detail, String? incidentId}) async {
    entries.insert(0, HistoryEntry(DateTime.now(), title, kind, detail: detail, incidentId: incidentId));
    if (entries.length > 150) entries = entries.sublist(0, 150);
    final p = await SharedPreferences.getInstance();
    await p.setString(_key, jsonEncode(entries.map((e) => e.toJson()).toList()));
    notifyListeners();
  }

  Future<void> clear() async {
    entries = [];
    final p = await SharedPreferences.getInstance();
    await p.remove(_key);
    notifyListeners();
  }
}
