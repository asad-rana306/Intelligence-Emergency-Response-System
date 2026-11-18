import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/models.dart';
import 'chat_service.dart';

/// Messages the user chose to keep (text and voice notes), on this phone.
/// They stay after the incident is over, and the user can delete them any time.
class SavedItem {
  final String id;
  final String incidentId;
  final String kind;
  final String? text;
  final String senderRole;
  final DateTime createdAt;
  final DateTime savedAt;
  final String? filePath;
  final int? durationMs;
  final String? note;

  SavedItem({
    required this.id,
    required this.incidentId,
    required this.kind,
    this.text,
    required this.senderRole,
    required this.createdAt,
    required this.savedAt,
    this.filePath,
    this.durationMs,
    this.note,
  });

  bool get isVoice => kind == 'VOICE';

  Map<String, dynamic> toJson() => {
    'id': id,
    'incidentId': incidentId,
    'kind': kind,
    'text': text,
    'senderRole': senderRole,
    'createdAt': createdAt.toIso8601String(),
    'savedAt': savedAt.toIso8601String(),
    'filePath': filePath,
    'durationMs': durationMs,
    'note': note,
  };

  factory SavedItem.fromJson(Map<String, dynamic> j) => SavedItem(
    id: j['id'] as String,
    incidentId: j['incidentId'] as String? ?? '',
    kind: j['kind'] as String? ?? 'TEXT',
    text: j['text'] as String?,
    senderRole: j['senderRole'] as String? ?? '',
    createdAt: DateTime.tryParse(j['createdAt'] as String? ?? '') ?? DateTime.now(),
    savedAt: DateTime.tryParse(j['savedAt'] as String? ?? '') ?? DateTime.now(),
    filePath: j['filePath'] as String?,
    durationMs: (j['durationMs'] as num?)?.toInt(),
    note: j['note'] as String?,
  );
}

class SavedStore extends ChangeNotifier {
  SavedStore._();
  static final SavedStore I = SavedStore._();

  static const _key = 'saved.items';
  List<SavedItem> items = [];

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    final raw = p.getString(_key);
    if (raw == null) return;
    try {
      items = (jsonDecode(raw) as List).map((j) => SavedItem.fromJson(Map<String, dynamic>.from(j as Map))).toList();
    } catch (_) {
      items = [];
    }
  }

  bool isSaved(ChatMessage m) => items.any((i) => i.id == (m.messageId ?? m.clientMessageId));

  /// Keeps a copy (a voice note is copied into the app's "saved" folder).
  Future<void> save(ChatMessage m) async {
    final id = m.messageId ?? m.clientMessageId;
    if (items.any((i) => i.id == id)) return;
    String? path;
    if (m.isVoice) {
      final f = await ChatService.I.audioFile(m);
      if (f == null) throw Exception('The voice note could not be downloaded');
      final base = await getApplicationDocumentsDirectory();
      final dir = Directory('${base.path}/saved');
      if (!await dir.exists()) await dir.create(recursive: true);
      path = (await f.copy('${dir.path}/$id.m4a')).path;
    }
    items.insert(
      0,
      SavedItem(
        id: id,
        incidentId: m.incidentId,
        kind: m.kind,
        text: m.text,
        senderRole: m.senderRole,
        createdAt: m.createdAt,
        savedAt: DateTime.now(),
        filePath: path,
        durationMs: m.durationMs,
      ),
    );
    await _persist();
  }

  Future<void> delete(SavedItem item) async {
    items.removeWhere((i) => i.id == item.id);
    final p = item.filePath;
    if (p != null) {
      try {
        await File(p).delete();
      } catch (_) {}
    }
    await _persist();
  }

  Future<void> deleteAll() async {
    for (final i in List.of(items)) {
      await delete(i);
    }
  }

  Future<void> _persist() async {
    final p = await SharedPreferences.getInstance();
    await p.setString(_key, jsonEncode(items.map((i) => i.toJson()).toList()));
    notifyListeners();
  }
}
