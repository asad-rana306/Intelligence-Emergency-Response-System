import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';

import '../../core/theme/app_theme.dart';
import '../../core/utils/fmt.dart';
import '../../core/widgets/common.dart';
import '../../services/saved_store.dart';
import '../chat/voice_player.dart';

/// Messages and voice notes kept on this phone (long-press a message → Save).
class SavedScreen extends StatelessWidget {
  const SavedScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final store = context.watch<SavedStore>();
    return Scaffold(
      appBar: AppBar(
        title: const Text('Saved messages'),
        actions: [
          if (store.items.isNotEmpty)
            IconButton(
              tooltip: 'Delete all',
              onPressed: () async {
                if (await confirm(
                  context,
                  title: 'Delete all saved?',
                  message: 'Voice notes are removed from this phone.',
                  yes: 'Delete',
                )) {
                  SavedStore.I.deleteAll();
                }
              },
              icon: const Icon(Icons.delete_sweep_rounded),
            ),
        ],
      ),
      body: store.items.isEmpty
          ? const Center(
              child: Padding(
                padding: EdgeInsets.all(32),
                child: Text(
                  'Nothing saved.\nIn a chat, long-press a message and choose Save.',
                  textAlign: TextAlign.center,
                  style: TextStyle(color: AppColors.muted, fontSize: 16),
                ),
              ),
            )
          : ListView.separated(
              padding: const EdgeInsets.all(16),
              itemCount: store.items.length,
              separatorBuilder: (_, _) => const SizedBox(height: 8),
              itemBuilder: (_, i) {
                final it = store.items[i];
                return Panel(
                  padding: const EdgeInsets.fromLTRB(14, 12, 6, 12),
                  child: Row(
                    children: [
                      Expanded(
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.start,
                          children: [
                            Row(
                              children: [
                                Pill(
                                  it.senderRole == 'RESPONDER' ? 'Responder' : 'Driver',
                                  color: it.senderRole == 'RESPONDER' ? AppColors.green : AppColors.red,
                                ),
                                const SizedBox(width: 8),
                                Text(
                                  Fmt.dateTime(it.createdAt),
                                  style: const TextStyle(color: AppColors.muted, fontSize: 12),
                                ),
                              ],
                            ),
                            const SizedBox(height: 8),
                            if (it.isVoice && it.filePath != null)
                              VoicePlayer(
                                load: () async => File(it.filePath!).readAsBytes(),
                                durationMs: it.durationMs,
                                color: AppColors.ink,
                              )
                            else
                              Text(it.text ?? '', style: const TextStyle(fontSize: 16)),
                          ],
                        ),
                      ),
                      Column(
                        children: [
                          if (!it.isVoice)
                            IconButton(
                              onPressed: () {
                                Clipboard.setData(ClipboardData(text: it.text ?? ''));
                                toast(context, 'Copied');
                              },
                              icon: const Icon(Icons.copy_rounded, size: 20, color: AppColors.muted),
                            ),
                          IconButton(
                            onPressed: () async {
                              if (await confirm(
                                context,
                                title: 'Delete?',
                                message: 'Removed from this phone.',
                                yes: 'Delete',
                              )) {
                                SavedStore.I.delete(it);
                              }
                            },
                            icon: const Icon(Icons.delete_outline_rounded, size: 20, color: AppColors.muted),
                          ),
                        ],
                      ),
                    ],
                  ),
                );
              },
            ),
    );
  }
}
