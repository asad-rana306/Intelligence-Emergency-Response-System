import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:path_provider/path_provider.dart';
import 'package:record/record.dart';

/// One recorded voice note, as bytes (works the same on phone and on Chrome).
class VoiceClip {
  final Uint8List bytes;
  final int durationMs;
  final String ext; // m4a (phone) / webm (web)
  final String contentType;
  const VoiceClip(this.bytes, this.durationMs, this.ext, this.contentType);
}

/// Records a short voice note. Small and clear on weak internet.
///  - Phone: AAC in an .m4a file, mono 16 kHz ~24 kbps.
///  - Web (Chrome): Opus/WebM from the browser recorder.
/// Either way `stop()` gives back the bytes, so the rest of the app never touches a file path.
class VoiceRecorder {
  final AudioRecorder _rec = AudioRecorder();
  DateTime? _startedAt;
  String? _path;

  static const maxLength = Duration(minutes: 2);

  bool get isRecording => _startedAt != null;
  Duration get elapsed => _startedAt == null ? Duration.zero : DateTime.now().difference(_startedAt!);

  Future<bool> start() async {
    if (!await _rec.hasPermission()) return false;
    if (kIsWeb) {
      // The browser records to memory; the path is ignored on web.
      await _rec.start(
        const RecordConfig(encoder: AudioEncoder.opus, bitRate: 24000, sampleRate: 16000, numChannels: 1),
        path: 'note',
      );
    } else {
      final dir = await getTemporaryDirectory();
      _path = '${dir.path}/note_${DateTime.now().millisecondsSinceEpoch}.m4a';
      await _rec.start(
        const RecordConfig(encoder: AudioEncoder.aacLc, bitRate: 24000, sampleRate: 16000, numChannels: 1),
        path: _path!,
      );
    }
    _startedAt = DateTime.now();
    return true;
  }

  /// Stops and returns the clip, or null if it was too short (< 0.8 s) or failed.
  Future<VoiceClip?> stop() async {
    final started = _startedAt;
    _startedAt = null;
    final out = await _rec.stop(); // mobile: file path. web: a blob: URL.
    if (started == null || out == null) return null;
    final ms = DateTime.now().difference(started).inMilliseconds;
    if (ms < 800) {
      await _cleanup(out);
      return null;
    }
    try {
      if (kIsWeb) {
        final bytes = await http.readBytes(Uri.parse(out));
        return VoiceClip(bytes, ms, 'webm', 'audio/webm');
      } else {
        final f = File(out);
        if (!await f.exists()) return null;
        final bytes = await f.readAsBytes();
        try {
          await f.delete();
        } catch (_) {}
        return VoiceClip(bytes, ms, 'm4a', 'audio/mp4');
      }
    } catch (_) {
      return null;
    }
  }

  Future<void> _cleanup(String out) async {
    try {
      if (!kIsWeb) await File(out).delete();
    } catch (_) {}
  }

  Future<void> cancel() async {
    _startedAt = null;
    try {
      await _rec.cancel();
    } catch (_) {}
  }

  Future<void> dispose() => _rec.dispose();
}
