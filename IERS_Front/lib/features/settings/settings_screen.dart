import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../../core/config/app_settings.dart';
import '../../core/theme/app_theme.dart';
import '../../core/utils/perms.dart';
import '../../core/widgets/common.dart';
import '../../services/alarm_service.dart';
import '../../services/auth_service.dart';

class SettingsScreen extends StatelessWidget {
  const SettingsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    final s = context.watch<AppSettings>();
    final auth = context.watch<AuthService>();
    final responder = auth.isResponder;

    return Scaffold(
      appBar: AppBar(title: const Text('Settings')),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 32),
        children: [
          Panel(
            child: Row(
              children: [
                Avatar(auth.fullName, color: responder ? AppColors.green : AppColors.red),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(auth.fullName ?? 'Me', style: Theme.of(context).textTheme.titleMedium),
                      Text(
                        '${auth.email ?? ''} · ${responder ? 'Responder' : 'Driver'}',
                        style: const TextStyle(color: AppColors.muted),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),

          if (!responder) ...[
            const SectionTitle('After a crash'),
            Panel(
              padding: EdgeInsets.zero,
              child: Column(
                children: [
                  SwitchListTile(
                    value: s.autoCallResponder,
                    onChanged: s.setAutoCall,
                    title: const Text('Call the rescue unit automatically'),
                    subtitle: const Text('When a unit accepts, the phone calls it (on speaker).'),
                  ),
                  SwitchListTile(
                    value: s.phoneCallFallback,
                    onChanged: s.setPhoneFallback,
                    title: const Text('Use a normal phone call if internet fails'),
                  ),
                  SwitchListTile(
                    value: s.shoutForHelp,
                    onChanged: s.setShout,
                    title: const Text('Announce loudly for people nearby'),
                    subtitle: Text('If nobody touches the phone for ${s.shoutDelaySeconds} s'),
                  ),
                  if (s.shoutForHelp)
                    Padding(
                      padding: const EdgeInsets.symmetric(horizontal: 16),
                      child: Row(
                        children: [
                          const Text('Wait'),
                          Expanded(
                            child: Slider(
                              value: s.shoutDelaySeconds.toDouble().clamp(10, 60),
                              min: 10,
                              max: 60,
                              divisions: 5,
                              label: '${s.shoutDelaySeconds} s',
                              onChanged: (v) => s.setShoutDelay(v.round()),
                            ),
                          ),
                          Text('${s.shoutDelaySeconds} s'),
                        ],
                      ),
                    ),
                  ListTile(
                    title: const Text('Announcement language'),
                    subtitle: const Text('The voice that calls people to help you'),
                    trailing: SegmentedButton<String>(
                      segments: const [
                        ButtonSegment(value: 'en', label: Text('English')),
                        ButtonSegment(value: 'ur', label: Text('اردو')),
                      ],
                      selected: {s.announceLanguage},
                      onSelectionChanged: (v) => s.setLanguage(v.first),
                    ),
                  ),
                  SwitchListTile(
                    value: s.autoDialRescue,
                    onChanged: s.setAutoDialRescue,
                    title: const Text('Dial 1122 if no unit accepts in 2 minutes'),
                  ),
                ],
              ),
            ),

            const SectionTitle('Phone as crash sensor'),
            Panel(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  const Text('Sensitivity', style: TextStyle(fontWeight: FontWeight.w800)),
                  const SizedBox(height: 8),
                  SegmentedButton<CrashSensitivity>(
                    segments: const [
                      ButtonSegment(value: CrashSensitivity.low, label: Text('Low')),
                      ButtonSegment(value: CrashSensitivity.normal, label: Text('Normal')),
                      ButtonSegment(value: CrashSensitivity.high, label: Text('High')),
                    ],
                    selected: {s.sensitivity},
                    onSelectionChanged: (v) => s.setSensitivity(v.first),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    'Low = fewer false alarms on bad roads. High = also small crashes.',
                    style: TextStyle(color: AppColors.muted, fontSize: 13),
                  ),
                ],
              ),
            ),
          ],

          if (responder) ...[
            const SectionTitle('Calls'),
            Panel(
              padding: EdgeInsets.zero,
              child: SwitchListTile(
                value: s.autoAnswerCalls,
                onChanged: s.setAutoAnswer,
                title: const Text('Answer patient calls by itself'),
                subtitle: const Text('Hands-free on speaker while you drive'),
              ),
            ),
          ],

          const SectionTitle('Test'),
          Row(
            children: [
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () async {
                    await AlarmService.I.siren();
                    await Future.delayed(const Duration(seconds: 3));
                    await AlarmService.I.stopLoop();
                  },
                  icon: const Icon(Icons.notifications_active_rounded),
                  label: const Text('Alarm'),
                ),
              ),
              const SizedBox(width: 8),
              Expanded(
                child: OutlinedButton.icon(
                  onPressed: () async {
                    await AlarmService.I.startShout();
                    await Future.delayed(const Duration(seconds: 8));
                    await AlarmService.I.stopShout();
                  },
                  icon: const Icon(Icons.campaign_rounded),
                  label: const Text('Announcement'),
                ),
              ),
            ],
          ),

          const SectionTitle('Phone permissions'),
          Panel(
            padding: EdgeInsets.zero,
            child: Column(
              children: [
                ListTile(
                  leading: const Icon(Icons.verified_user_rounded),
                  title: const Text('Allow everything IERS needs'),
                  subtitle: const Text('Location, microphone, phone, notifications'),
                  onTap: () async {
                    await Perms.requestAll();
                    if (context.mounted) toast(context, 'Done');
                  },
                ),
                ListTile(
                  leading: const Icon(Icons.battery_saver_rounded),
                  title: const Text('Do not let the phone sleep the app'),
                  subtitle: const Text('Keeps alerts working in the background'),
                  onTap: Perms.ignoreBatteryOptimizations,
                ),
                ListTile(
                  leading: const Icon(Icons.settings_applications_rounded),
                  title: const Text('Open app settings'),
                  onTap: Perms.openSettings,
                ),
              ],
            ),
          ),

          const SizedBox(height: 24),
          OutlinedButton.icon(
            style: OutlinedButton.styleFrom(foregroundColor: AppColors.red),
            onPressed: () async {
              if (await confirm(
                context,
                title: 'Log out?',
                message: 'You will not get alerts on this phone.',
                yes: 'Log out',
              )) {
                await AuthService.I.logout();
                if (context.mounted) Navigator.of(context).popUntil((r) => r.isFirst);
              }
            },
            icon: const Icon(Icons.logout_rounded),
            label: const Text('Log out'),
          ),
          const SizedBox(height: 12),
          const Center(
            child: Text('IERS 2.0', style: TextStyle(color: AppColors.muted)),
          ),
        ],
      ),
    );
  }
}
