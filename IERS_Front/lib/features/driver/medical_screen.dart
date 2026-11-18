import 'package:flutter/material.dart';

import '../../core/net/api_exception.dart';
import '../../core/theme/app_theme.dart';
import '../../core/widgets/common.dart';
import '../../models/models.dart';
import '../../services/profile_service.dart';

/// Medical ID: what a responder (and a bystander) needs to know about you.
class MedicalScreen extends StatefulWidget {
  const MedicalScreen({super.key});

  @override
  State<MedicalScreen> createState() => _MedicalScreenState();
}

class _MedicalScreenState extends State<MedicalScreen> {
  static const _bloodTypes = ['A+', 'A-', 'B+', 'B-', 'AB+', 'AB-', 'O+', 'O-', 'Unknown'];
  final m = ProfileService.I.medical;
  late String? _blood = _bloodTypes.contains(m.bloodType) ? m.bloodType : null;
  late final _allergies = TextEditingController(text: m.allergies ?? '');
  late final _meds = TextEditingController(text: m.medications ?? '');
  late final _conditions = TextEditingController(text: m.chronicConditions ?? '');
  late final _notes = TextEditingController(text: m.emergencyNotes ?? '');
  bool _busy = false;

  Future<void> _save() async {
    setState(() => _busy = true);
    try {
      await ProfileService.I.saveMedical(
        MedicalProfile(
          bloodType: _blood,
          allergies: _allergies.text.trim(),
          medications: _meds.text.trim(),
          chronicConditions: _conditions.text.trim(),
          emergencyNotes: _notes.text.trim(),
        ),
      );
      if (mounted) {
        toast(context, 'Medical ID saved');
        Navigator.pop(context);
      }
    } on ApiException catch (e) {
      if (mounted) toast(context, e.message, error: true);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Medical ID')),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          const Text(
            'Shown to the rescue unit that accepts your emergency, and on your crash screen for people helping you.',
            style: TextStyle(color: AppColors.muted),
          ),
          const SectionTitle('Blood type'),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final b in _bloodTypes)
                ChoiceChip(
                  label: Text(b, style: const TextStyle(fontWeight: FontWeight.w800)),
                  selected: _blood == b,
                  selectedColor: AppColors.red.withValues(alpha: 0.2),
                  onSelected: (_) => setState(() => _blood = b),
                ),
            ],
          ),
          const SectionTitle('Details'),
          TextField(
            controller: _allergies,
            decoration: const InputDecoration(labelText: 'Allergies', hintText: 'e.g. Penicillin'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _meds,
            decoration: const InputDecoration(labelText: 'Medicines you take', hintText: 'e.g. Insulin'),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _conditions,
            decoration: const InputDecoration(
              labelText: 'Conditions',
              hintText: 'e.g. Diabetes, asthma, heart problem',
            ),
          ),
          const SizedBox(height: 12),
          TextField(
            controller: _notes,
            maxLines: 3,
            decoration: const InputDecoration(
              labelText: 'Notes for the rescue team',
              hintText: 'e.g. I wear contact lenses',
            ),
          ),
          const SizedBox(height: 24),
          FilledButton(
            onPressed: _busy ? null : _save,
            child: _busy ? const CircularProgressIndicator(color: Colors.white) : const Text('Save'),
          ),
        ],
      ),
    );
  }
}
