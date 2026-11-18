import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../controllers/driver_controller.dart';
import '../../controllers/responder_controller.dart';
import '../../core/net/session.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/fmt.dart';
import '../../core/widgets/common.dart';
import '../../models/models.dart';
import '../../services/call_service.dart';
import '../../services/chat_service.dart';
import '../../services/launcher.dart';
import '../../services/saved_store.dart';
import '../../services/voice_recorder.dart';
import 'voice_player.dart';

/// WhatsApp-style chat of one emergency: text, quick replies, voice notes.
/// Messages wait and re-send by themselves on a weak connection.
class ChatScreen extends StatefulWidget {
  const ChatScreen({super.key, required this.incidentId, this.title});
  final String incidentId;
  final String? title;

  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final _text = TextEditingController();
  final _recorder = VoiceRecorder();
  Timer? _recTimer;
  bool _recording = false;

  bool get _isResponder => Session.I.isResponder;

  List<String> get _quick => _isResponder
      ? const [
          'On my way',
          '2 minutes away',
          'Stay still, do not move',
          'Turn on hazard lights',
          'Is anyone bleeding?',
          'Can you breathe OK?',
        ]
      : const [
          'I am conscious',
          'I am bleeding',
          'I cannot move',
          'Fire / smoke',
          'A child is with me',
          'Please hurry',
        ];

  @override
  void initState() {
    super.initState();
    ChatService.I.openIncident = widget.incidentId;
    ChatService.I.load(widget.incidentId);
    ChatService.I.markRead(widget.incidentId);
  }

  @override
  void dispose() {
    if (ChatService.I.openIncident == widget.incidentId) ChatService.I.openIncident = null;
    _recTimer?.cancel();
    if (_recording) _recorder.cancel();
    _recorder.dispose();
    super.dispose();
  }

  // ── peer (for the call buttons) ──
  String? get _peerPhone {
    if (_isResponder) {
      final m = ResponderController.I.mission;
      return m?.id == widget.incidentId ? m?.driverPhone : null;
    }
    final d = DriverController.I;
    return d.incidentId == widget.incidentId ? d.responder?.phone : null;
  }

  String get _peerName {
    if (widget.title != null) return widget.title!;
    if (_isResponder) return ResponderController.I.mission?.driverName ?? 'Driver';
    return DriverController.I.responder?.name ?? 'Rescue unit';
  }

  bool get _canCall {
    if (_isResponder) return ResponderController.I.mission?.id == widget.incidentId;
    final d = DriverController.I;
    return d.incidentId == widget.incidentId && d.responder != null;
  }

  Future<void> _send([String? quick]) async {
    final t = (quick ?? _text.text).trim();
    if (t.isEmpty) return;
    if (quick == null) _text.clear();
    HapticFeedback.selectionClick();
    await ChatService.I.sendText(widget.incidentId, t);
    setState(() {});
  }

  Future<void> _startRecording() async {
    if (!await _recorder.start()) {
      if (mounted) toast(context, 'Allow the microphone to record voice messages.', error: true);
      return;
    }
    HapticFeedback.mediumImpact();
    setState(() => _recording = true);
    _recTimer = Timer.periodic(const Duration(milliseconds: 250), (_) {
      if (_recorder.elapsed >= VoiceRecorder.maxLength) {
        _stopRecording(send: true);
      } else {
        setState(() {});
      }
    });
  }

  Future<void> _stopRecording({required bool send}) async {
    _recTimer?.cancel();
    setState(() => _recording = false);
    if (!send) {
      await _recorder.cancel();
      return;
    }
    final clip = await _recorder.stop();
    if (clip == null) {
      if (mounted) toast(context, 'Too short. Tap the mic, speak, then tap send.');
      return;
    }
    await ChatService.I.sendVoice(widget.incidentId, clip);
  }

  @override
  Widget build(BuildContext context) {
    final chat = context.watch<ChatService>();
    context.watch<SavedStore>();
    final msgs = chat.messages(widget.incidentId).reversed.toList();
    final me = Session.I.userId;
    return Scaffold(
      appBar: AppBar(
        titleSpacing: 0,
        title: Row(
          children: [
            Avatar(_peerName, size: 38, color: _isResponder ? AppColors.red : AppColors.green),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    _peerName,
                    style: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
                    overflow: TextOverflow.ellipsis,
                  ),
                  Text(
                    _isResponder ? 'Patient' : 'Rescue unit',
                    style: const TextStyle(fontSize: 12, color: AppColors.muted, fontWeight: FontWeight.w600),
                  ),
                ],
              ),
            ),
          ],
        ),
        actions: [
          if (_canCall)
            IconButton(
              tooltip: 'Internet call',
              onPressed: () => CallService.I.call(incidentId: widget.incidentId, name: _peerName, phone: _peerPhone),
              icon: const Icon(Icons.call_rounded, color: AppColors.green),
            ),
          if ((_peerPhone ?? '').isNotEmpty)
            IconButton(
              tooltip: 'Phone call',
              onPressed: () => Launcher.callNow(_peerPhone!),
              icon: const Icon(Icons.phone_in_talk_rounded),
            ),
        ],
      ),
      body: Column(
        children: [
          Expanded(
            child: msgs.isEmpty
                ? const Center(
                    child: Padding(
                      padding: EdgeInsets.all(32),
                      child: Text(
                        'No messages yet.\nSend a quick reply or a voice message.',
                        textAlign: TextAlign.center,
                        style: TextStyle(color: AppColors.muted, fontSize: 16),
                      ),
                    ),
                  )
                : ListView.builder(
                    reverse: true,
                    padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
                    itemCount: msgs.length,
                    itemBuilder: (_, i) => _Bubble(m: msgs[i], mine: msgs[i].senderId == me),
                  ),
          ),
          SizedBox(
            height: 46,
            child: ListView.separated(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
              itemCount: _quick.length,
              separatorBuilder: (_, _) => const SizedBox(width: 6),
              itemBuilder: (_, i) => ActionChip(
                label: Text(_quick[i], style: const TextStyle(fontWeight: FontWeight.w700)),
                onPressed: () => _send(_quick[i]),
              ),
            ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(8, 4, 8, 8),
              child: _recording ? _recordingBar() : _composer(),
            ),
          ),
        ],
      ),
    );
  }

  Widget _composer() {
    return ValueListenableBuilder<TextEditingValue>(
      valueListenable: _text,
      builder: (_, v, _) => Row(
        children: [
          Expanded(
            child: TextField(
              controller: _text,
              minLines: 1,
              maxLines: 4,
              textCapitalization: TextCapitalization.sentences,
              decoration: InputDecoration(
                hintText: 'Message',
                border: OutlineInputBorder(borderRadius: BorderRadius.circular(26), borderSide: BorderSide.none),
                enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(26), borderSide: BorderSide.none),
                focusedBorder: OutlineInputBorder(
                  borderRadius: BorderRadius.circular(26),
                  borderSide: const BorderSide(color: AppColors.red),
                ),
              ),
              onSubmitted: (_) => _send(),
            ),
          ),
          const SizedBox(width: 6),
          SizedBox(
            width: 52,
            height: 52,
            child: FloatingActionButton(
              heroTag: 'chat-send',
              elevation: 0,
              backgroundColor: AppColors.red,
              onPressed: v.text.trim().isEmpty ? _startRecording : () => _send(),
              child: Icon(v.text.trim().isEmpty ? Icons.mic_rounded : Icons.send_rounded, color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }

  Widget _recordingBar() {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
      decoration: BoxDecoration(color: AppColors.red.withValues(alpha: 0.1), borderRadius: BorderRadius.circular(28)),
      child: Row(
        children: [
          IconButton(
            tooltip: 'Delete',
            onPressed: () => _stopRecording(send: false),
            icon: const Icon(Icons.delete_outline_rounded, color: AppColors.muted),
          ),
          const Icon(Icons.fiber_manual_record_rounded, color: AppColors.red, size: 16),
          const SizedBox(width: 6),
          Text('Recording  ${Fmt.duration(_recorder.elapsed)}', style: const TextStyle(fontWeight: FontWeight.w800)),
          const Spacer(),
          SizedBox(
            width: 52,
            height: 52,
            child: FloatingActionButton(
              heroTag: 'chat-rec-send',
              elevation: 0,
              backgroundColor: AppColors.green,
              onPressed: () => _stopRecording(send: true),
              child: const Icon(Icons.send_rounded, color: Colors.white),
            ),
          ),
        ],
      ),
    );
  }
}

class _Bubble extends StatelessWidget {
  const _Bubble({required this.m, required this.mine});
  final ChatMessage m;
  final bool mine;

  Future<void> _menu(BuildContext context) async {
    final saved = SavedStore.I.isSaved(m);
    final choice = await showModalBottomSheet<String>(
      context: context,
      showDragHandle: true,
      builder: (c) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            if (m.state == MessageState.failed)
              ListTile(
                leading: const Icon(Icons.refresh_rounded),
                title: const Text('Send again'),
                onTap: () => Navigator.pop(c, 'retry'),
              ),
            if (!saved && m.state == MessageState.sent)
              ListTile(
                leading: const Icon(Icons.bookmark_add_rounded),
                title: const Text('Save'),
                onTap: () => Navigator.pop(c, 'save'),
              ),
            if (!m.isVoice)
              ListTile(
                leading: const Icon(Icons.copy_rounded),
                title: const Text('Copy text'),
                onTap: () => Navigator.pop(c, 'copy'),
              ),
            ListTile(
              leading: const Icon(Icons.delete_outline_rounded),
              title: const Text('Delete from this phone'),
              onTap: () => Navigator.pop(c, 'delete'),
            ),
          ],
        ),
      ),
    );
    if (!context.mounted) return;
    switch (choice) {
      case 'retry':
        ChatService.I.retry(m);
        break;
      case 'save':
        try {
          await SavedStore.I.save(m);
          if (context.mounted) toast(context, 'Saved. Find it under Saved messages.');
        } catch (e) {
          if (context.mounted) toast(context, 'Could not save: $e', error: true);
        }
        break;
      case 'copy':
        await Clipboard.setData(ClipboardData(text: m.text ?? ''));
        if (context.mounted) toast(context, 'Copied');
        break;
      case 'delete':
        ChatService.I.deleteLocal(m);
        break;
    }
  }

  @override
  Widget build(BuildContext context) {
    final bg = mine ? AppColors.red : Theme.of(context).cardTheme.color ?? Colors.white;
    final fg = mine ? Colors.white : (Theme.of(context).brightness == Brightness.dark ? Colors.white : AppColors.ink);
    final saved = SavedStore.I.isSaved(m);
    return Align(
      alignment: mine ? Alignment.centerRight : Alignment.centerLeft,
      child: GestureDetector(
        onLongPress: () => _menu(context),
        onTap: m.state == MessageState.failed ? () => _menu(context) : null,
        child: Container(
          margin: const EdgeInsets.symmetric(vertical: 3),
          padding: const EdgeInsets.fromLTRB(12, 9, 12, 7),
          constraints: BoxConstraints(maxWidth: MediaQuery.of(context).size.width * 0.78),
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.only(
              topLeft: const Radius.circular(18),
              topRight: const Radius.circular(18),
              bottomLeft: Radius.circular(mine ? 18 : 4),
              bottomRight: Radius.circular(mine ? 4 : 18),
            ),
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.end,
            mainAxisSize: MainAxisSize.min,
            children: [
              if (m.isVoice)
                VoicePlayer(load: () => ChatService.I.audioBytes(m), durationMs: m.durationMs, color: fg)
              else
                Text(m.text ?? '', style: TextStyle(color: fg, fontSize: 16, height: 1.3)),
              const SizedBox(height: 3),
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  if (saved) Icon(Icons.bookmark_rounded, size: 13, color: fg.withValues(alpha: 0.7)),
                  Text(' ${Fmt.clock(m.createdAt)}', style: TextStyle(color: fg.withValues(alpha: 0.7), fontSize: 11)),
                  if (mine) ...[
                    const SizedBox(width: 4),
                    Icon(
                      switch (m.state) {
                        MessageState.sending => Icons.schedule_rounded,
                        MessageState.sent => Icons.done_rounded,
                        MessageState.failed => Icons.error_rounded,
                      },
                      size: 14,
                      color: m.state == MessageState.failed ? Colors.yellowAccent : fg.withValues(alpha: 0.8),
                    ),
                  ],
                ],
              ),
              if (m.state == MessageState.failed)
                Text(
                  m.error ?? 'Not sent. Tap to send again.',
                  style: TextStyle(
                    color: mine ? Colors.yellowAccent : AppColors.red,
                    fontSize: 11,
                    fontWeight: FontWeight.w700,
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}
