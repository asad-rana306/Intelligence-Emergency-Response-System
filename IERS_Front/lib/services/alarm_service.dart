import 'dart:async';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';
import 'package:flutter_tts/flutter_tts.dart';
import 'package:vibration/vibration.dart';
import 'package:volume_controller/volume_controller.dart';

import '../core/config/app_settings.dart';

/// Every sound the app makes: crash siren, new-offer ring, call ring-back, the loud
/// announcement for bystanders, and vibration.
///
/// The announcement voice:
///  - If you put real recordings in assets/sounds (announce_en.mp3 / announce_ur.mp3),
///    those play — a real human voice.
///  - Otherwise it speaks with the phone's text-to-speech, tuned to sound as natural as possible.
class AlarmService {
  AlarmService._();
  static final AlarmService I = AlarmService._();

  final AudioPlayer _loop = AudioPlayer(playerId: 'alarm-loop');
  final AudioPlayer _short = AudioPlayer(playerId: 'alarm-short');
  final AudioPlayer _voice = AudioPlayer(playerId: 'alarm-voice');
  final FlutterTts _tts = FlutterTts();
  Timer? _shoutTimer;
  bool _shouting = false;
  String? _looping;

  bool get isShouting => _shouting;

  /// The alarm stream on Android plays even when the phone is on silent.
  static final _alarmContext = AudioContext(
    android: const AudioContextAndroid(
      isSpeakerphoneOn: true,
      stayAwake: true,
      contentType: AndroidContentType.sonification,
      usageType: AndroidUsageType.alarm,
      audioFocus: AndroidAudioFocus.gain,
    ),
    iOS: AudioContextIOS(category: AVAudioSessionCategory.playback, options: const {}),
  );

  /// Turn the phone's volume up loud (so a siren / announcement is heard across a crash scene).
  Future<void> loud() async {
    if (kIsWeb) return;
    try {
      VolumeController.instance.showSystemUI = false;
      await VolumeController.instance.setVolume(1.0);
    } catch (_) {}
  }

  Future<void> siren() async {
    await loud();
    await _playLoop('sounds/siren.wav', vibrate: const [0, 700, 300]);
  }

  Future<void> offerRing() => _playLoop('sounds/offer.wav', vibrate: const [0, 400, 200, 400, 900]);
  Future<void> ringback() => _playLoop('sounds/ringback.wav');

  Future<void> beep() async {
    try {
      await _short.stop();
      await _short.play(AssetSource('sounds/beep.wav'), volume: 0.8);
    } catch (_) {}
  }

  Future<void> stopLoop() async {
    _looping = null;
    try {
      await _loop.stop();
    } catch (_) {}
    try {
      await Vibration.cancel();
    } catch (_) {}
  }

  Future<void> tapFeedback() => HapticFeedback.heavyImpact();

  // ── loud announcement for bystanders ──

  static const _english =
      'Emergency! There has been a car accident. If you can hear this, please pick up this phone '
      'and press the green button to talk to the rescue team. Do not move the injured person.';
  static const _urdu =
      'ایمرجنسی! یہاں گاڑی کا حادثہ ہوا ہے۔ اگر آپ یہ سن رہے ہیں تو براہ کرم یہ فون اٹھائیں '
      'اور سبز بٹن دبا کر ریسکیو ٹیم سے بات کریں۔ زخمی کو مت ہلائیں۔';

  /// Repeats the announcement every [every] until [stopShout].
  Future<void> startShout({Duration every = const Duration(seconds: 14)}) async {
    if (_shouting) return;
    _shouting = true;
    await loud();
    await stopLoop();
    await _sayOnce();
    _shoutTimer?.cancel();
    _shoutTimer = Timer.periodic(every, (_) => _sayOnce());
  }

  Future<void> stopShout() async {
    _shouting = false;
    _shoutTimer?.cancel();
    _shoutTimer = null;
    try {
      await _tts.stop();
    } catch (_) {}
    try {
      await _voice.stop();
    } catch (_) {}
  }

  /// One spoken sentence (e.g. "Help is on the way").
  Future<void> say(String text) async {
    try {
      await _setupTts();
      await _tts.speak(text);
    } catch (_) {}
  }

  Future<void> stopAll() async {
    await stopShout();
    await stopLoop();
  }

  // ── internals ──

  Future<void> _playLoop(String asset, {List<int>? vibrate}) async {
    if (_looping == asset) return;
    _looping = asset;
    try {
      await _loop.stop();
      await _loop.setAudioContext(_alarmContext);
      await _loop.setReleaseMode(ReleaseMode.loop);
      await _loop.play(AssetSource(asset), volume: 1.0);
    } catch (_) {}
    if (vibrate != null) {
      try {
        if (await Vibration.hasVibrator() == true) {
          await Vibration.vibrate(pattern: vibrate, repeat: 0);
        }
      } catch (_) {}
    }
  }

  bool _ttsReady = false;
  String? _ttsLang;
  // Remembers whether a real recording exists for a language, so we only probe once.
  final Map<String, bool> _clipOk = {};

  Future<void> _setupTts() async {
    final wanted = AppSettings.I.announceLanguage == 'ur' ? 'ur-PK' : 'en-US';
    if (_ttsReady && _ttsLang == wanted) return;
    try {
      var lang = wanted;
      if (wanted == 'ur-PK') {
        final ok = await _tts.isLanguageAvailable('ur-PK');
        if (ok != true) lang = 'en-US';
      }
      await _tts.setLanguage(lang);
      await _tts.setVolume(1.0);
      await _tts.setSpeechRate(0.44); // a touch slower = clearer, more human
      await _tts.setPitch(1.0);
      await _tts.awaitSpeakCompletion(true);
      await _pickNaturalVoice(lang);
      _ttsLang = wanted;
      _ttsReady = true;
    } catch (_) {}
  }

  /// Choose the most natural installed voice (many phones ship an "enhanced"/"neural" one).
  Future<void> _pickNaturalVoice(String lang) async {
    try {
      final voices = (await _tts.getVoices as List?) ?? const [];
      final code = lang.split('-').first;
      Map? best;
      for (final v in voices) {
        final m = Map<String, dynamic>.from(v as Map);
        final locale = (m['locale'] ?? '').toString().toLowerCase();
        if (!locale.startsWith(code)) continue;
        final name = (m['name'] ?? '').toString().toLowerCase();
        final nice = name.contains('enhanced') ||
            name.contains('neural') ||
            name.contains('premium') ||
            name.contains('natural');
        best ??= m;
        if (nice) {
          best = m;
          break;
        }
      }
      if (best != null) {
        await _tts.setVoice({'name': '${best['name']}', 'locale': '${best['locale']}'});
      }
    } catch (_) {}
  }

  Future<void> _sayOnce() async {
    if (!_shouting) return;
    final ur = AppSettings.I.announceLanguage == 'ur';
    // 1) Real human recording, if one was bundled.
    if (await _tryClip(ur ? 'announce_ur' : 'announce_en')) return;
    // 2) Otherwise speak with text-to-speech.
    await _setupTts();
    final urdu = ur && _ttsLang == 'ur-PK';
    try {
      await _tts.speak(urdu ? _urdu : _english);
    } catch (_) {}
  }

  /// Plays `assets/sounds/NAME.mp3` loudly if it exists. Returns false if there is none.
  Future<bool> _tryClip(String name) async {
    if (_clipOk[name] == false) return false;
    try {
      await _voice.stop();
      await _voice.setAudioContext(_alarmContext);
      await _voice.play(AssetSource('sounds/$name.mp3'), volume: 1.0);
      _clipOk[name] = true;
      return true;
    } catch (_) {
      _clipOk[name] = false;
      return false;
    }
  }
}
