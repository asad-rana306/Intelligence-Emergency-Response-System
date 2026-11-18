import 'dart:async';
import 'dart:typed_data';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/material.dart';

import '../../core/utils/fmt.dart';

/// Play button + progress for one voice note. The audio is fetched only when played.
class VoicePlayer extends StatefulWidget {
  const VoicePlayer({super.key, required this.load, this.durationMs, this.color = Colors.black87});
  final Future<Uint8List?> Function() load;
  final int? durationMs;
  final Color color;

  @override
  State<VoicePlayer> createState() => _VoicePlayerState();
}

class _VoicePlayerState extends State<VoicePlayer> {
  final _player = AudioPlayer();
  final List<StreamSubscription> _subs = [];
  Duration _pos = Duration.zero;
  Duration? _dur;
  bool _playing = false;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    if (widget.durationMs != null) _dur = Duration(milliseconds: widget.durationMs!);
    _subs.add(_player.onPositionChanged.listen((p) => setState(() => _pos = p)));
    _subs.add(_player.onDurationChanged.listen((d) => setState(() => _dur = d)));
    _subs.add(
      _player.onPlayerComplete.listen(
        (_) => setState(() {
          _playing = false;
          _pos = Duration.zero;
        }),
      ),
    );
  }

  @override
  void dispose() {
    for (final s in _subs) {
      s.cancel();
    }
    _player.dispose();
    super.dispose();
  }

  Future<void> _toggle() async {
    if (_playing) {
      await _player.pause();
      setState(() => _playing = false);
      return;
    }
    if (_pos > Duration.zero) {
      await _player.resume();
      setState(() => _playing = true);
      return;
    }
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final bytes = await widget.load();
      if (bytes == null) throw Exception('not available');
      await _player.play(BytesSource(bytes));
      setState(() => _playing = true);
    } catch (_) {
      setState(() => _error = 'Could not load. Tap to retry.');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final total = _dur ?? Duration.zero;
    final frac = total.inMilliseconds == 0 ? 0.0 : (_pos.inMilliseconds / total.inMilliseconds).clamp(0.0, 1.0);
    return SizedBox(
      width: 210,
      child: Row(
        children: [
          InkResponse(
            onTap: _loading ? null : _toggle,
            radius: 24,
            child: _loading
                ? SizedBox(
                    width: 32,
                    height: 32,
                    child: Padding(
                      padding: const EdgeInsets.all(6),
                      child: CircularProgressIndicator(strokeWidth: 2.5, color: widget.color),
                    ),
                  )
                : Icon(
                    _playing ? Icons.pause_circle_filled_rounded : Icons.play_circle_fill_rounded,
                    size: 36,
                    color: widget.color,
                  ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                ClipRRect(
                  borderRadius: BorderRadius.circular(4),
                  child: LinearProgressIndicator(
                    value: frac,
                    minHeight: 4,
                    color: widget.color,
                    backgroundColor: widget.color.withValues(alpha: 0.2),
                  ),
                ),
                const SizedBox(height: 4),
                Text(
                  _error ?? Fmt.duration(_playing || _pos > Duration.zero ? _pos : total),
                  style: TextStyle(
                    fontSize: 12,
                    color: widget.color.withValues(alpha: 0.8),
                    fontWeight: FontWeight.w600,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
