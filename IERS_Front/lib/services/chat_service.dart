import 'dart:async';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:path_provider/path_provider.dart';
import 'package:uuid/uuid.dart';

import '../core/net/api_client.dart';
import '../core/net/api_exception.dart';
import '../core/net/session.dart';
import '../models/models.dart';
import 'connectivity_service.dart';
import 'live_service.dart';
import 'voice_recorder.dart';

/// Chat of one incident (driver ↔ responder): text + voice notes.
///
/// Built for a weak connection:
///  - a message shows at once ("sending" clock), and is re-sent by itself until it goes through,
///  - every message has its own id (clientMessageId), so a re-send never shows twice,
///  - voice notes are small (24 kbps) and are downloaded only when played.
class ChatService extends ChangeNotifier {
  ChatService._();
  static final ChatService I = ChatService._();

  final Map<String, List<ChatMessage>> _byIncident = {};
  final Map<String, int> unread = {};
  String? openIncident; // the chat screen that is open now
  StreamSubscription? _live;
  StreamSubscription? _back;
  Timer? _retry;
  final _uuid = const Uuid();

  void start() {
    _live ??= LiveService.I.events.listen((e) {
      if (e.type == 'CHAT_MESSAGE') _merge(ChatMessage.fromJson(e.data), fromLive: true);
    });
    _back ??= ConnectivityService.I.onBack.listen((_) => retryAll());
    _retry ??= Timer.periodic(const Duration(seconds: 8), (_) => retryAll());
  }

  List<ChatMessage> messages(String incidentId) => _byIncident[incidentId] ?? const [];
  int unreadFor(String incidentId) => unread[incidentId] ?? 0;

  /// New messages from the other person since the chat was last opened (for a badge / sound).
  final _incoming = StreamController<ChatMessage>.broadcast();
  Stream<ChatMessage> get incoming => _incoming.stream;

  Future<void> load(String incidentId) async {
    try {
      final list = await ApiClient.I.get('/api/incidents/$incidentId/messages');
      if (list is List) {
        for (final j in list) {
          _merge(ChatMessage.fromJson(Map<String, dynamic>.from(j as Map)), notify: false);
        }
        notifyListeners();
      }
    } on ApiException catch (_) {
      // Offline or not allowed: keep what we have.
    }
  }

  void markRead(String incidentId) {
    if ((unread[incidentId] ?? 0) == 0) return;
    unread[incidentId] = 0;
    notifyListeners();
  }

  Future<void> sendText(String incidentId, String text) async {
    final t = text.trim();
    if (t.isEmpty) return;
    final m = ChatMessage(
      incidentId: incidentId,
      clientMessageId: _uuid.v4(),
      senderId: Session.I.userId ?? '',
      senderRole: Session.I.role ?? 'DRIVER',
      kind: 'TEXT',
      text: t,
      createdAt: DateTime.now(),
      state: MessageState.sending,
    );
    _add(m);
    await _deliver(m);
  }

  Future<void> sendVoice(String incidentId, VoiceClip clip) async {
    final id = _uuid.v4();
    final m = ChatMessage(
      incidentId: incidentId,
      clientMessageId: id,
      senderId: Session.I.userId ?? '',
      senderRole: Session.I.role ?? 'DRIVER',
      kind: 'VOICE',
      durationMs: clip.durationMs,
      createdAt: DateTime.now(),
      state: MessageState.sending,
    );
    // Keep the audio in memory so it plays at once (and on the web, where there is no file).
    m.localAudioBytes = clip.bytes;
    m.localAudioMime = clip.contentType;
    if (!kIsWeb) {
      // On a phone, also keep a copy on disk so it survives an app restart.
      try {
        final dir = await _voiceDir(incidentId);
        final f = File('${dir.path}/$id.${clip.ext}');
        await f.writeAsBytes(clip.bytes, flush: true);
        m.localAudioPath = f.path;
      } catch (_) {}
    }
    _add(m);
    await _deliver(m);
  }

  Future<void> retry(ChatMessage m) async {
    m.state = MessageState.sending;
    m.error = null;
    notifyListeners();
    await _deliver(m);
  }

  void retryAll() {
    for (final list in _byIncident.values) {
      for (final m in list.where((m) => m.state == MessageState.failed && m.error == null).toList()) {
        retry(m);
      }
    }
  }

  /// Audio bytes of a voice note, for playing (works on web and phone).
  /// Uses the in-memory copy, then a saved file (phone), then downloads from the server.
  Future<Uint8List?> audioBytes(ChatMessage m) async {
    if (m.localAudioBytes != null) return m.localAudioBytes;
    final local = m.localAudioPath;
    if (!kIsWeb && local != null) {
      try {
        final f = File(local);
        if (await f.exists()) {
          final b = await f.readAsBytes();
          m.localAudioBytes = b;
          return b;
        }
      } catch (_) {}
    }
    final url = m.audioUrl;
    if (url == null) return null;
    final bytes = Uint8List.fromList(await ApiClient.I.download(url));
    m.localAudioBytes = bytes;
    if (!kIsWeb) {
      try {
        final dir = await _voiceDir(m.incidentId);
        final f = File('${dir.path}/${m.messageId ?? m.clientMessageId}.m4a');
        await f.writeAsBytes(bytes, flush: true);
        m.localAudioPath = f.path;
      } catch (_) {}
    }
    return bytes;
  }

  /// Local file of a voice note — phone only (used when saving a note to disk).
  Future<File?> audioFile(ChatMessage m) async {
    if (kIsWeb) return null;
    final local = m.localAudioPath;
    if (local != null && await File(local).exists()) return File(local);
    final bytes = await audioBytes(m);
    if (bytes == null) return null;
    final dir = await _voiceDir(m.incidentId);
    final f = File('${dir.path}/${m.messageId ?? m.clientMessageId}.m4a');
    await f.writeAsBytes(bytes, flush: true);
    m.localAudioPath = f.path;
    return f;
  }

  /// Removes a message from this phone only (the server keeps the history).
  Future<void> deleteLocal(ChatMessage m) async {
    _byIncident[m.incidentId]?.remove(m);
    final p = m.localAudioPath;
    if (p != null) {
      try {
        await File(p).delete();
      } catch (_) {}
    }
    notifyListeners();
  }

  // ───────────────────────────── internals ─────────────────────────────

  Future<void> _deliver(ChatMessage m) async {
    try {
      dynamic reply;
      if (m.isVoice) {
        final bytes = m.localAudioBytes ?? (m.localAudioPath != null ? await File(m.localAudioPath!).readAsBytes() : null);
        if (bytes == null) {
          m.state = MessageState.failed;
          notifyListeners();
          return;
        }
        final mime = m.localAudioMime ?? 'audio/mp4';
        reply = await ApiClient.I.uploadBytes(
          '/api/incidents/${m.incidentId}/messages/voice',
          bytes: bytes,
          fileField: 'file',
          filename: 'note.${mime.endsWith('webm') ? 'webm' : 'm4a'}',
          contentType: mime,
          fields: {'durationMs': '${m.durationMs ?? 0}', 'clientMessageId': m.clientMessageId},
        );
      } else {
        reply = await ApiClient.I.post('/api/incidents/${m.incidentId}/messages', {
          'text': m.text,
          'clientMessageId': m.clientMessageId,
        });
      }
      if (reply is Map) {
        final saved = ChatMessage.fromJson(Map<String, dynamic>.from(reply));
        m.messageId = saved.messageId;
        m.audioUrl = saved.audioUrl;
      }
      m.state = MessageState.sent;
    } on ApiException catch (e) {
      m.state = MessageState.failed;
      // A 4xx (chat closed, not allowed) will never go by itself: keep the reason, no auto re-send.
      m.error = (!e.isOffline && e.status >= 400 && e.status < 500 && e.status != 408 && e.status != 429)
          ? e.message
          : null;
    } catch (_) {
      m.state = MessageState.failed;
    }
    notifyListeners();
  }

  void _add(ChatMessage m) {
    _byIncident.putIfAbsent(m.incidentId, () => []).add(m);
    notifyListeners();
  }

  void _merge(ChatMessage m, {bool notify = true, bool fromLive = false}) {
    if (m.incidentId.isEmpty) return;
    final list = _byIncident.putIfAbsent(m.incidentId, () => []);
    final i = list.indexWhere(
      (x) => (m.messageId != null && x.messageId == m.messageId) || x.clientMessageId == m.clientMessageId,
    );
    if (i >= 0) {
      final mine = list[i];
      mine.messageId ??= m.messageId;
      mine.audioUrl ??= m.audioUrl;
      if (mine.state != MessageState.sent) mine.state = MessageState.sent;
    } else {
      list.add(m);
      list.sort((a, b) => a.createdAt.compareTo(b.createdAt));
      final fromOther = m.senderId != Session.I.userId;
      if (fromLive && fromOther) {
        if (openIncident != m.incidentId) unread[m.incidentId] = (unread[m.incidentId] ?? 0) + 1;
        _incoming.add(m);
      }
    }
    if (notify) notifyListeners();
  }

  Future<Directory> _voiceDir(String incidentId) async {
    final base = await getApplicationDocumentsDirectory();
    final d = Directory('${base.path}/voice/$incidentId');
    if (!await d.exists()) await d.create(recursive: true);
    return d;
  }
}
