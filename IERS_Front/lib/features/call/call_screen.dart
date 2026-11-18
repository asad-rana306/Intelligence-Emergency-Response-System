import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:provider/provider.dart';
import 'package:wakelock_plus/wakelock_plus.dart';

import '../../core/theme/app_theme.dart';
import '../../core/utils/fmt.dart';
import '../../core/widgets/common.dart';
import '../../services/call_service.dart';

/// Full-screen call: incoming (accept / decline), calling, connected (mute, speaker, phone, hang up).
class CallScreen extends StatefulWidget {
  const CallScreen({super.key});

  @override
  State<CallScreen> createState() => _CallScreenState();
}

class _CallScreenState extends State<CallScreen> {
  bool _closing = false;
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    if (!kIsWeb) WakelockPlus.enable();
    _tick = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted) setState(() {});
    });
  }

  @override
  void dispose() {
    _tick?.cancel();
    if (!kIsWeb) WakelockPlus.disable();
    super.dispose();
  }

  String _status(CallService c) {
    switch (c.phase) {
      case CallPhase.incoming:
        return 'Incoming call…';
      case CallPhase.outgoing:
        return 'Calling…';
      case CallPhase.connecting:
        return 'Connecting…';
      case CallPhase.active:
        return Fmt.duration(c.elapsed);
      case CallPhase.ended:
        return c.endReason ?? 'Call ended';
      case CallPhase.idle:
        return '';
    }
  }

  @override
  Widget build(BuildContext context) {
    final c = context.watch<CallService>();
    if (c.phase == CallPhase.idle && !_closing) {
      _closing = true;
      WidgetsBinding.instance.addPostFrameCallback((_) {
        if (mounted) closeOwnPage(context);
      });
    }
    final incoming = c.phase == CallPhase.incoming;
    final ended = c.phase == CallPhase.ended;
    final active = c.phase == CallPhase.active;

    return PopScope(
      canPop: !c.busy,
      child: Scaffold(
        backgroundColor: Colors.white,
        body: Stack(
          children: [
            // Invisible audio sink — the other person's voice plays through this (needed on web).
            if (c.remoteRenderer != null)
              SizedBox(width: 1, height: 1, child: RTCVideoView(c.remoteRenderer!)),
            SafeArea(
              child: LayoutBuilder(
                builder: (context, constraints) {
                  return SingleChildScrollView(
                    physics: const ClampingScrollPhysics(),
                    child: ConstrainedBox(
                      constraints: BoxConstraints(minHeight: constraints.maxHeight),
                      child: IntrinsicHeight(
                        child: Padding(
                          padding: const EdgeInsets.symmetric(horizontal: 24, vertical: 24),
                          child: Column(
                            children: [
                              const SizedBox(height: 8),
                              Text(
                                active ? 'On call' : 'IERS call',
                                style: const TextStyle(color: AppColors.muted, fontWeight: FontWeight.w700),
                              ),
                              const SizedBox(height: 28),
                              Avatar(c.peerName, size: 108, color: AppColors.green),
                              const SizedBox(height: 18),
                              Text(
                                c.peerName ?? 'Emergency contact',
                                textAlign: TextAlign.center,
                                style: const TextStyle(
                                  color: AppColors.ink,
                                  fontSize: 27,
                                  fontWeight: FontWeight.w900,
                                ),
                              ),
                              const SizedBox(height: 8),
                              Text(
                                _status(c),
                                style: TextStyle(
                                  color: ended ? AppColors.red : AppColors.muted,
                                  fontSize: 17,
                                  fontWeight: FontWeight.w600,
                                ),
                              ),
                              const Spacer(),
                              const SizedBox(height: 24),
                              if (ended && (c.peerPhone ?? '').isNotEmpty) ...[
                                FilledButton.icon(
                                  style: FilledButton.styleFrom(backgroundColor: AppColors.blue),
                                  onPressed: c.phoneCall,
                                  icon: const Icon(Icons.phone_in_talk_rounded),
                                  label: const Text('Call by phone instead (no internet needed)'),
                                ),
                                const SizedBox(height: 12),
                                TextButton(
                                  onPressed: () => Navigator.pop(context),
                                  child: const Text('Close'),
                                ),
                              ] else if (incoming)
                                Row(
                                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                                  children: [
                                    _RoundButton(
                                      icon: Icons.call_end_rounded,
                                      color: AppColors.red,
                                      label: 'Decline',
                                      onTap: c.decline,
                                    ),
                                    _RoundButton(
                                      icon: Icons.call_rounded,
                                      color: AppColors.green,
                                      label: 'Answer',
                                      onTap: c.accept,
                                      big: true,
                                    ),
                                  ],
                                )
                              else if (!ended) ...[
                                Row(
                                  mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                                  children: [
                                    _RoundButton(
                                      icon: c.muted ? Icons.mic_off_rounded : Icons.mic_rounded,
                                      color: c.muted ? AppColors.red : const Color(0xFFEFF1F4),
                                      iconColor: c.muted ? Colors.white : AppColors.ink,
                                      label: c.muted ? 'Unmute' : 'Mute',
                                      onTap: c.toggleMute,
                                    ),
                                    if (!kIsWeb)
                                      _RoundButton(
                                        icon: c.speaker ? Icons.volume_up_rounded : Icons.hearing_rounded,
                                        color: c.speaker ? AppColors.blue : const Color(0xFFEFF1F4),
                                        iconColor: c.speaker ? Colors.white : AppColors.ink,
                                        label: c.speaker ? 'Speaker' : 'Earpiece',
                                        onTap: c.toggleSpeaker,
                                      ),
                                    _RoundButton(
                                      icon: Icons.phone_forwarded_rounded,
                                      color: const Color(0xFFEFF1F4),
                                      iconColor: AppColors.ink,
                                      label: 'Phone call',
                                      onTap: (c.peerPhone ?? '').isEmpty ? null : c.phoneCall,
                                    ),
                                  ],
                                ),
                                const SizedBox(height: 24),
                                _RoundButton(
                                  icon: Icons.call_end_rounded,
                                  color: AppColors.red,
                                  label: 'Hang up',
                                  onTap: c.hangup,
                                  big: true,
                                ),
                              ],
                            ],
                          ),
                        ),
                      ),
                    ),
                  );
                },
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _RoundButton extends StatelessWidget {
  const _RoundButton({
    required this.icon,
    required this.color,
    required this.label,
    required this.onTap,
    this.big = false,
    this.iconColor = Colors.white,
  });

  final IconData icon;
  final Color color;
  final Color iconColor;
  final String label;
  final VoidCallback? onTap;
  final bool big;

  @override
  Widget build(BuildContext context) {
    final size = big ? 74.0 : 58.0;
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Material(
          color: onTap == null ? const Color(0xFFF1F2F4) : color,
          shape: const CircleBorder(),
          child: InkWell(
            customBorder: const CircleBorder(),
            onTap: onTap,
            child: SizedBox(
              width: size,
              height: size,
              child: Icon(icon, color: onTap == null ? AppColors.muted : iconColor, size: big ? 34 : 26),
            ),
          ),
        ),
        const SizedBox(height: 8),
        Text(
          label,
          style: const TextStyle(color: AppColors.muted, fontWeight: FontWeight.w600, fontSize: 13),
        ),
      ],
    );
  }
}
