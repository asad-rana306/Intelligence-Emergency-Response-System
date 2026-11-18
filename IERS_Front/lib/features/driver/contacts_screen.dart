import 'package:flutter/material.dart';
import 'package:flutter_contacts/flutter_contacts.dart';
import 'package:provider/provider.dart';

import '../../core/net/api_exception.dart';
import '../../core/theme/app_theme.dart';
import '../../core/widgets/common.dart';
import '../../models/models.dart';
import '../../services/launcher.dart';
import '../../services/profile_service.dart';

/// Emergency contacts screen (Step 2).
///
/// Adding a contact:
///   1) Offer "Pick from phonebook" OR "Type manually".
///   2) On either path, show a confirmation dialog with the exact number
///      (because the crash alert SMS will go there).
///   3) Only after the user confirms, the contact is saved.
class ContactsScreen extends StatelessWidget {
  const ContactsScreen({super.key});

  Future<void> _add(BuildContext context) async {
    // ── 1. Ask which way: phonebook or type ──
    final source = await showModalBottomSheet<_AddSource>(
      context: context,
      showDragHandle: true,
      builder: (c) => SafeArea(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            const Padding(
              padding: EdgeInsets.fromLTRB(20, 4, 20, 8),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Text('Add an emergency contact',
                    style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
              ),
            ),
            ListTile(
              leading: const Icon(Icons.contacts_rounded, color: AppColors.green),
              title: const Text('Pick from my phone contacts'),
              subtitle: const Text('Recommended'),
              onTap: () => Navigator.pop(c, _AddSource.phonebook),
            ),
            ListTile(
              leading: const Icon(Icons.dialpad_rounded, color: AppColors.red),
              title: const Text('Type a number manually'),
              onTap: () => Navigator.pop(c, _AddSource.manual),
            ),
            const SizedBox(height: 10),
          ],
        ),
      ),
    );
    if (source == null || !context.mounted) return;

    _Draft? draft;
    if (source == _AddSource.phonebook) {
      draft = await _pickFromPhonebook(context);
    } else {
      draft = await _enterManually(context);
    }
    if (draft == null || !context.mounted) return;

    // ── 3. Confirm the exact number before saving ──
    final confirmed = await _confirm(context, draft);
    if (!confirmed || !context.mounted) return;

    try {
      await ProfileService.I.addContact(draft.name, draft.phone, draft.relationship);
      if (context.mounted) toast(context, 'Contact added');
    } on ApiException catch (e) {
      if (context.mounted) toast(context, e.message, error: true);
    }
  }

  // ─── Phonebook flow ───────────────────────────────────────────────

  Future<_Draft?> _pickFromPhonebook(BuildContext context) async {
    try {
      final ok = await FlutterContacts.requestPermission(readonly: true);
      if (!ok) {
        if (context.mounted) {
          toast(context, 'Permission denied. You can type the number instead.', error: true);
        }
        return null;
      }
      // withProperties = true so each contact actually has phones.
      final c = await FlutterContacts.openExternalPick();
      if (c == null) return null;

      // Reload with phones (openExternalPick returns a thin contact).
      final full = await FlutterContacts.getContact(c.id, withProperties: true);
      final phones = full?.phones ?? [];
      if (phones.isEmpty) {
        if (context.mounted) toast(context, 'That contact has no phone number', error: true);
        return null;
      }

      String phone;
      if (phones.length == 1) {
        phone = phones.first.number;
      } else {
        final chosen = await showModalBottomSheet<String>(
          context: context,
          showDragHandle: true,
          builder: (bc) => SafeArea(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Padding(
                  padding: EdgeInsets.all(16),
                  child: Text('Which number?',
                      style: TextStyle(fontSize: 18, fontWeight: FontWeight.w800)),
                ),
                for (final p in phones)
                  ListTile(
                    leading: const Icon(Icons.phone_rounded),
                    title: Text(p.number),
                    subtitle: Text(p.label.name),
                    onTap: () => Navigator.pop(bc, p.number),
                  ),
              ],
            ),
          ),
        );
        if (chosen == null) return null;
        phone = chosen;
      }

      final normalized = _toE164(phone);
      return _Draft(
        name: full?.displayName ?? c.displayName,
        phone: normalized,
        relationship: 'Family',
      );
    } catch (e) {
      if (context.mounted) {
        toast(context, 'Could not open contacts: $e', error: true);
      }
      return null;
    }
  }

  // ─── Manual flow ──────────────────────────────────────────────────

  Future<_Draft?> _enterManually(BuildContext context) async {
    final name = TextEditingController();
    final phone = TextEditingController(text: '+92');
    String relation = 'Family';
    final ok = await showModalBottomSheet<bool>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (c) => StatefulBuilder(
        builder: (c, set) => Padding(
          padding: EdgeInsets.fromLTRB(20, 0, 20, 20 + MediaQuery.of(c).viewInsets.bottom),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text('New emergency contact', style: Theme.of(c).textTheme.titleLarge),
              const SizedBox(height: 14),
              TextField(
                controller: name,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(labelText: 'Name'),
              ),
              const SizedBox(height: 10),
              TextField(
                controller: phone,
                keyboardType: TextInputType.phone,
                decoration: const InputDecoration(
                  labelText: 'Mobile number',
                  helperText: 'Include country code, e.g. +923001234567',
                ),
              ),
              const SizedBox(height: 10),
              Wrap(
                spacing: 8,
                children: [
                  for (final r in const ['Family', 'Spouse', 'Parent', 'Friend', 'Colleague'])
                    ChoiceChip(
                      label: Text(r),
                      selected: relation == r,
                      onSelected: (_) => set(() => relation = r),
                    ),
                ],
              ),
              const SizedBox(height: 16),
              FilledButton(
                onPressed: () {
                  if (name.text.trim().isEmpty) return;
                  if (phone.text.replaceAll(RegExp(r'[^0-9]'), '').length < 10) return;
                  Navigator.pop(c, true);
                },
                child: const Text('Next'),
              ),
            ],
          ),
        ),
      ),
    );
    if (ok != true) return null;
    return _Draft(
      name: name.text.trim(),
      phone: _toE164(phone.text.trim()),
      relationship: relation,
    );
  }

  // ─── Confirmation dialog ─────────────────────────────────────────

  Future<bool> _confirm(BuildContext context, _Draft d) async {
    return await showDialog<bool>(
      context: context,
      barrierDismissible: false,
      builder: (c) => AlertDialog(
        title: const Text('Is this number correct?'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(d.name, style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
            const SizedBox(height: 4),
            Text(d.phone, style: const TextStyle(fontSize: 22, fontWeight: FontWeight.w800)),
            const SizedBox(height: 12),
            const Text(
              'The accident alert SMS will be sent to this number. '
              'If it is wrong, they will not know when something happens.',
              style: TextStyle(color: AppColors.muted),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('Change')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('Yes, save')),
        ],
      ),
    ) ?? false;
  }

  // ─── Pakistan-friendly E.164 normalization ──────────────────────

  static String _toE164(String raw) {
    var s = raw.trim();
    s = s.replaceAll(RegExp(r'[\s\-()]'), '');
    if (s.startsWith('00')) s = '+${s.substring(2)}';
    if (s.startsWith('0')) s = '+92${s.substring(1)}';
    if (RegExp(r'^3\d{9}$').hasMatch(s)) s = '+92$s'; // 3001234567 (no leading 0)
    if (!s.startsWith('+')) s = '+$s';
    return s;
  }

  @override
  Widget build(BuildContext context) {
    final p = context.watch<ProfileService>();
    return Scaffold(
      appBar: AppBar(title: const Text('Emergency contacts')),
      floatingActionButton: FloatingActionButton.extended(
        onPressed: () => _add(context),
        icon: const Icon(Icons.person_add_alt_1_rounded),
        label: const Text('Add'),
      ),
      body: RefreshIndicator(
        onRefresh: ProfileService.I.refresh,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(16, 8, 16, 100),
          children: [
            const Text(
              'After a crash they get an SMS from IERS with where you are.',
              style: TextStyle(color: AppColors.muted),
            ),
            const SizedBox(height: 12),
            if (p.contacts.isEmpty)
              const Panel(
                child: Text('No contacts yet. Add at least one.',
                    style: TextStyle(fontWeight: FontWeight.w600)),
              ),
            for (final c in p.contacts) ...[_ContactRow(c: c), const SizedBox(height: 8)],
          ],
        ),
      ),
    );
  }
}

enum _AddSource { phonebook, manual }

class _Draft {
  _Draft({required this.name, required this.phone, required this.relationship});
  final String name;
  final String phone;
  final String relationship;
}

class _ContactRow extends StatelessWidget {
  const _ContactRow({required this.c});
  final EmergencyContact c;

  @override
  Widget build(BuildContext context) => Panel(
    padding: const EdgeInsets.fromLTRB(14, 10, 6, 10),
    child: Row(
      children: [
        Avatar(c.name, color: AppColors.green),
        const SizedBox(width: 12),
        Expanded(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Text(c.name,
                  style: const TextStyle(fontWeight: FontWeight.w800, fontSize: 16)),
              Text('${c.relationship ?? ''} · ${c.phone}',
                  style: const TextStyle(color: AppColors.muted)),
            ],
          ),
        ),
        IconButton(
          onPressed: () => Launcher.callNow(c.phone),
          icon: const Icon(Icons.call_rounded, color: AppColors.green),
        ),
        IconButton(
          onPressed: () async {
            if (await confirm(
              context,
              title: 'Remove ${c.name}?',
              message: 'They will not get crash SMS any more.',
              yes: 'Remove',
            )) {
              try {
                await ProfileService.I.deleteContact(c);
              } on ApiException catch (e) {
                if (context.mounted) toast(context, e.message, error: true);
              }
            }
          },
          icon: const Icon(Icons.delete_outline_rounded, color: AppColors.muted),
        ),
      ],
    ),
  );
}
