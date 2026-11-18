import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';

import '../core/config/app_settings.dart';
import '../core/net/session.dart';
import '../core/utils/perms.dart';
import '../models/models.dart';
import 'alarm_service.dart';
import 'launcher.dart';
import 'live_service.dart';

enum CallPhase { idle, outgoing, incoming, connecting, active, ended }

/// Internet voice call between the driver and the assigned responder (like WhatsApp).
///
/// Set-up messages go through the Realtime service (RING → ACCEPT → OFFER → ANSWER → ICE);
/// the voice goes phone to phone (WebRTC, Opus at ~20 kbps so it works on weak 3G/4G).
/// If the other phone is offline, or the call cannot connect, it can fall back to a normal
/// phone call (GSM), which needs no internet at all.
class CallService extends ChangeNotifier {
  CallService._();
  static final CallService I = CallService._();

  CallPhase phase = CallPhase.idle;
  String? incidentId;
  String? peerName;
  String? peerPhone;
  String? endReason;
  bool muted = false;
  bool speaker = true;
  DateTime? startedAt;
  bool autoStarted = false;

  /// The app shows the call screen when this turns true (set by an incoming RING).
  final ValueNotifier<bool> showIncoming = ValueNotifier(false);

  RTCPeerConnection? _pc;
  MediaStream? _local;
  MediaStream? _remote;
  // On web the remote voice only plays when its stream is mounted in a renderer.
  // The call screen renders this in a 1x1 (invisible) view so you can hear the other side.
  RTCVideoRenderer? remoteRenderer;
  bool _remoteSet = false;
  final List<RTCIceCandidate> _pendingIce = [];
  Timer? _noAnswer;
  StreamSubscription? _callSub;
  StreamSubscription? _eventSub;
  bool _iAmCaller = false;

  bool get busy => phase != CallPhase.idle && phase != CallPhase.ended;
  Duration get elapsed => startedAt == null ? Duration.zero : DateTime.now().difference(startedAt!);

  void start() {
    _callSub ??= LiveService.I.calls.listen(_onSignal);
    _eventSub ??= LiveService.I.events.listen((e) {
      if (e.type == 'CALL_ERROR') _onCallError(e.data);
    });
  }

  /// Start a call. [auto] = started by the app itself (after a crash), not by a tap.
  Future<void> call({required String incidentId, String? name, String? phone, bool auto = false}) async {
    if (busy) return;
    if (!await _micAllowed()) {
      _end('Microphone permission is needed for calls');
      return;
    }
    this.incidentId = incidentId;
    peerName = name;
    peerPhone = phone;
    autoStarted = auto;
    _iAmCaller = true;
    _setPhase(CallPhase.outgoing);
    if (!LiveService.I.sendCall(incidentId, 'RING')) {
      _failOver('No internet for a call');
      return;
    }
    AlarmService.I.ringback();
    _noAnswer?.cancel();
    _noAnswer = Timer(const Duration(seconds: 30), () {
      if (phase == CallPhase.outgoing || phase == CallPhase.connecting) {
        _send('HANGUP');
        _failOver('No answer');
      }
    });
  }

  Future<void> accept() async {
    if (phase != CallPhase.incoming || incidentId == null) return;
    showIncoming.value = false;
    await AlarmService.I.stopLoop();
    if (!await _micAllowed()) {
      decline();
      return;
    }
    _setPhase(CallPhase.connecting);
    _send('ACCEPT');
    await _preparePeer();
  }

  void decline() {
    if (phase != CallPhase.incoming) return;
    showIncoming.value = false;
    _send('DECLINE');
    _end('Declined');
  }

  void hangup() {
    if (!busy) return;
    _send('HANGUP');
    _end('Call ended');
  }

  void toggleMute() {
    muted = !muted;
    for (final t in _local?.getAudioTracks() ?? const <MediaStreamTrack>[]) {
      t.enabled = !muted;
    }
    notifyListeners();
  }

  Future<void> toggleSpeaker() async {
    speaker = !speaker;
    try {
      await Helper.setSpeakerphoneOn(speaker);
    } catch (_) {}
    notifyListeners();
  }

  /// Normal phone call to the same person (no internet needed).
  Future<void> phoneCall() async {
    final number = peerPhone;
    if (busy) hangup();
    if (number != null && number.isNotEmpty) await Launcher.callNow(number);
  }

  // ───────────────────────────── signalling ─────────────────────────────

  Future<void> _onSignal(CallSignal s) async {
    switch (s.kind) {
      case 'RING':
        await _onRing(s);
        break;
      case 'ACCEPT':
        if (s.incidentId != incidentId || !_iAmCaller) return;
        await AlarmService.I.stopLoop();
        _setPhase(CallPhase.connecting);
        await _preparePeer();
        await _makeOffer();
        break;
      case 'DECLINE':
        if (s.incidentId != incidentId) return;
        _failOver('They could not answer');
        break;
      case 'OFFER':
        if (s.incidentId != incidentId) return;
        await _preparePeer();
        await _pc?.setRemoteDescription(
          RTCSessionDescription(s.payload['sdp'] as String?, s.payload['type'] as String?),
        );
        _remoteSet = true;
        await _flushIce();
        final answer = await _pc!.createAnswer({});
        final tuned = RTCSessionDescription(_lowBitrate(answer.sdp), answer.type);
        await _pc!.setLocalDescription(tuned);
        _send('ANSWER', {'sdp': tuned.sdp, 'type': tuned.type});
        break;
      case 'ANSWER':
        if (s.incidentId != incidentId || _pc == null) return;
        await _pc!.setRemoteDescription(
          RTCSessionDescription(s.payload['sdp'] as String?, s.payload['type'] as String?),
        );
        _remoteSet = true;
        await _flushIce();
        break;
      case 'ICE':
        if (s.incidentId != incidentId) return;
        final c = RTCIceCandidate(
          s.payload['candidate'] as String?,
          s.payload['sdpMid'] as String?,
          (s.payload['sdpMLineIndex'] as num?)?.toInt(),
        );
        if (_pc != null && _remoteSet) {
          await _pc!.addCandidate(c);
        } else {
          _pendingIce.add(c);
        }
        break;
      case 'HANGUP':
        if (s.incidentId != incidentId) return;
        showIncoming.value = false;
        _end('Call ended');
        break;
    }
  }

  Future<void> _onRing(CallSignal s) async {
    // Both pressed "call" at the same moment: the higher user id answers the other's call.
    if (phase == CallPhase.outgoing && s.incidentId == incidentId) {
      final me = Session.I.userId ?? '';
      if (me.compareTo(s.from ?? '') > 0) {
        _iAmCaller = false;
        _noAnswer?.cancel();
        await AlarmService.I.stopLoop();
        _setPhase(CallPhase.connecting);
        _send('ACCEPT');
        await _preparePeer();
      }
      return;
    }
    if (busy) {
      LiveService.I.sendCall(s.incidentId, 'DECLINE', {'reason': 'BUSY'});
      return;
    }
    incidentId = s.incidentId;
    _iAmCaller = false;
    autoStarted = false;
    _setPhase(CallPhase.incoming);
    showIncoming.value = true;
    if (Session.I.isResponder && AppSettings.I.autoAnswerCalls) {
      // Hands-free for a responder who is driving: answer on speaker after one ring.
      AlarmService.I.beep();
      Future.delayed(const Duration(milliseconds: 1500), accept);
    } else {
      AlarmService.I.offerRing();
    }
  }

  void _onCallError(Map<String, dynamic> d) {
    if (d['incidentId']?.toString() != incidentId) return;
    final reason = d['reason']?.toString();
    if (phase == CallPhase.outgoing || phase == CallPhase.connecting) {
      _failOver(
        reason == 'PEER_OFFLINE'
            ? 'They are not online right now'
            : reason == 'INCIDENT_NOT_ACTIVE'
            ? 'Calls work after a unit accepts'
            : 'Call not possible',
      );
    }
  }

  // ───────────────────────────── WebRTC ─────────────────────────────

  Future<void> _preparePeer() async {
    if (_pc != null) return;
    try {
      final ice = <Map<String, dynamic>>[
        {'urls': 'stun:stun.l.google.com:19302'},
        {'urls': 'stun:stun1.l.google.com:19302'},
      ];
      final s = AppSettings.I;
      if (s.turnUrl.isNotEmpty) {
        ice.add({'urls': s.turnUrl, 'username': s.turnUser, 'credential': s.turnPass});
      }
      // Remote-audio sink. Needed on the web for the voice to actually play.
      remoteRenderer ??= RTCVideoRenderer();
      await remoteRenderer!.initialize();

      _pc = await createPeerConnection({'iceServers': ice, 'sdpSemantics': 'unified-plan'});
      _local = await navigator.mediaDevices.getUserMedia({
        'audio': {'echoCancellation': true, 'noiseSuppression': true, 'autoGainControl': true},
        'video': false,
      });
      for (final t in _local!.getAudioTracks()) {
        await _pc!.addTrack(t, _local!);
      }
      // Play the other person's voice when it arrives.
      _pc!.onTrack = (event) {
        if (event.streams.isNotEmpty) {
          _remote = event.streams[0];
          remoteRenderer?.srcObject = _remote;
          notifyListeners();
        }
      };
    } catch (e) {
      // e.g. browser without WebRTC, or the plugin failed to load on the web.
      _failOver('This device could not start an internet call');
      return;
    }
    _pc!.onIceCandidate = (c) {
      if (c.candidate == null) return;
      _send('ICE', {'candidate': c.candidate, 'sdpMid': c.sdpMid, 'sdpMLineIndex': c.sdpMLineIndex});
    };
    _pc!.onConnectionState = (state) {
      if (state == RTCPeerConnectionState.RTCPeerConnectionStateConnected) {
        _noAnswer?.cancel();
        AlarmService.I.stopLoop();
        startedAt ??= DateTime.now();
        _setPhase(CallPhase.active);
      } else if (state == RTCPeerConnectionState.RTCPeerConnectionStateFailed) {
        _failOver('The internet connection is too weak for a call');
      }
    };
    // Some Android versions only report the ICE state, so watch both.
    _pc!.onIceConnectionState = (state) {
      if (state == RTCIceConnectionState.RTCIceConnectionStateConnected ||
          state == RTCIceConnectionState.RTCIceConnectionStateCompleted) {
        if (phase != CallPhase.active) {
          _noAnswer?.cancel();
          AlarmService.I.stopLoop();
          startedAt ??= DateTime.now();
          _setPhase(CallPhase.active);
        }
      } else if (state == RTCIceConnectionState.RTCIceConnectionStateFailed) {
        _failOver('The internet connection is too weak for a call');
      }
    };
    try {
      await Helper.setSpeakerphoneOn(speaker);
    } catch (_) {}
  }

  Future<void> _makeOffer() async {
    final offer = await _pc!.createOffer({'offerToReceiveAudio': 1, 'offerToReceiveVideo': 0});
    final tuned = RTCSessionDescription(_lowBitrate(offer.sdp), offer.type);
    await _pc!.setLocalDescription(tuned);
    _send('OFFER', {'sdp': tuned.sdp, 'type': tuned.type});
  }

  Future<void> _flushIce() async {
    for (final c in List.of(_pendingIce)) {
      try {
        await _pc?.addCandidate(c);
      } catch (_) {}
    }
    _pendingIce.clear();
  }

  /// Opus with forward error correction and ~20 kbps: clear voice on a weak connection.
  static String? _lowBitrate(String? sdp) {
    if (sdp == null) return sdp;
    final pt = RegExp(r'a=rtpmap:(\d+) opus/48000').firstMatch(sdp)?.group(1);
    if (pt == null) return sdp;
    final fmtp = RegExp('a=fmtp:$pt ([^\\r\\n]*)');
    final m = fmtp.firstMatch(sdp);
    if (m == null) return sdp;
    var params = m.group(1)!;
    if (!params.contains('maxaveragebitrate')) params += ';maxaveragebitrate=20000';
    if (!params.contains('useinbandfec')) params += ';useinbandfec=1';
    if (!params.contains('usedtx')) params += ';usedtx=1';
    return sdp.replaceFirst(fmtp, 'a=fmtp:$pt $params');
  }

  // ───────────────────────────── helpers ─────────────────────────────

  void _send(String kind, [Map<String, dynamic>? payload]) {
    final id = incidentId;
    if (id != null) LiveService.I.sendCall(id, kind, payload);
  }

  /// The internet call did not work. Offer (or start) a normal phone call.
  void _failOver(String reason) {
    if (phase == CallPhase.ended || phase == CallPhase.idle) return;
    final number = peerPhone;
    final auto = autoStarted;
    _end(reason);
    if (AppSettings.I.phoneCallFallback && auto && number != null && number.isNotEmpty) {
      Launcher.callNow(number);
    }
  }

  Future<bool> _micAllowed() => Perms.microphone();

  void _setPhase(CallPhase p) {
    phase = p;
    if (p != CallPhase.ended) endReason = null;
    notifyListeners();
  }

  Future<void> _end(String reason) async {
    _noAnswer?.cancel();
    showIncoming.value = false;
    await AlarmService.I.stopLoop();
    endReason = reason;
    phase = CallPhase.ended;
    notifyListeners();
    try {
      for (final t in _local?.getTracks() ?? const <MediaStreamTrack>[]) {
        await t.stop();
      }
      await _local?.dispose();
      remoteRenderer?.srcObject = null;
      await remoteRenderer?.dispose();
      await _pc?.close();
    } catch (_) {}
    _local = null;
    _remote = null;
    remoteRenderer = null;
    _pc = null;
    _remoteSet = false;
    _pendingIce.clear();
    startedAt = null;
    muted = false;
    Future.delayed(const Duration(seconds: 3), () {
      if (phase == CallPhase.ended) {
        phase = CallPhase.idle;
        notifyListeners();
      }
    });
  }
}
