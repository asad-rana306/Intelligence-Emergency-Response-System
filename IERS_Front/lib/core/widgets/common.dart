import 'package:flutter/material.dart';

import '../theme/app_theme.dart';
import '../utils/fmt.dart';

/// A white rounded card with padding.
class Panel extends StatelessWidget {
  const Panel({super.key, required this.child, this.padding = const EdgeInsets.all(16), this.color, this.onTap});
  final Widget child;
  final EdgeInsets padding;
  final Color? color;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final card = Card(
      color: color,
      clipBehavior: Clip.antiAlias,
      child: onTap == null
          ? Padding(padding: padding, child: child)
          : InkWell(
              onTap: onTap,
              child: Padding(padding: padding, child: child),
            ),
    );
    return card;
  }
}

/// Small coloured pill: "ON DUTY", "CRITICAL", "Sending…".
class Pill extends StatelessWidget {
  const Pill(this.text, {super.key, this.color = AppColors.blue, this.icon, this.filled = false});
  final String text;
  final Color color;
  final IconData? icon;
  final bool filled;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 5),
      decoration: BoxDecoration(
        color: filled ? color : color.withValues(alpha: 0.12),
        borderRadius: BorderRadius.circular(999),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (icon != null) ...[Icon(icon, size: 14, color: filled ? Colors.white : color), const SizedBox(width: 4)],
          Text(
            text,
            style: TextStyle(
              color: filled ? Colors.white : color,
              fontWeight: FontWeight.w800,
              fontSize: 12,
              letterSpacing: 0.3,
            ),
          ),
        ],
      ),
    );
  }
}

/// Big square-ish action: icon + label (+ optional caption).
class ActionTile extends StatelessWidget {
  const ActionTile({
    super.key,
    required this.icon,
    required this.label,
    required this.onTap,
    this.caption,
    this.color = AppColors.ink,
    this.background,
    this.badge,
  });
  final IconData icon;
  final String label;
  final String? caption;
  final VoidCallback? onTap;
  final Color color;
  final Color? background;
  final int? badge;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: background ?? Theme.of(context).cardTheme.color,
      borderRadius: BorderRadius.circular(18),
      child: InkWell(
        borderRadius: BorderRadius.circular(18),
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 14),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Badge(
                isLabelVisible: (badge ?? 0) > 0,
                label: Text('${badge ?? 0}'),
                child: Icon(icon, size: 28, color: onTap == null ? AppColors.muted : color),
              ),
              const SizedBox(height: 8),
              Text(
                label,
                textAlign: TextAlign.center,
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontWeight: FontWeight.w800, color: onTap == null ? AppColors.muted : color),
              ),
              if (caption != null)
                Text(
                  caption!,
                  textAlign: TextAlign.center,
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(fontSize: 12, color: AppColors.muted),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class Avatar extends StatelessWidget {
  const Avatar(this.name, {super.key, this.size = 48, this.color = AppColors.blue, this.icon});
  final String? name;
  final double size;
  final Color color;
  final IconData? icon;

  @override
  Widget build(BuildContext context) => CircleAvatar(
    radius: size / 2,
    backgroundColor: color.withValues(alpha: 0.15),
    child: icon != null
        ? Icon(icon, color: color, size: size * 0.5)
        : Text(
            Fmt.initials(name),
            style: TextStyle(color: color, fontWeight: FontWeight.w900, fontSize: size * 0.36),
          ),
  );
}

class SectionTitle extends StatelessWidget {
  const SectionTitle(this.text, {super.key, this.trailing});
  final String text;
  final Widget? trailing;

  @override
  Widget build(BuildContext context) => Padding(
    padding: const EdgeInsets.fromLTRB(4, 18, 4, 8),
    child: Row(
      children: [
        Expanded(
          child: Text(
            text.toUpperCase(),
            style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w800, color: AppColors.muted, letterSpacing: 1),
          ),
        ),
        ?trailing,
      ],
    ),
  );
}

/// "Are you sure?" with a red or green main button. Returns true on yes.
Future<bool> confirm(
  BuildContext context, {
  required String title,
  required String message,
  String yes = 'Yes',
  String no = 'No',
  bool danger = true,
}) async {
  final r = await showDialog<bool>(
    context: context,
    builder: (c) => AlertDialog(
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(22)),
      title: Text(title, style: const TextStyle(fontWeight: FontWeight.w900)),
      content: Text(message, style: const TextStyle(fontSize: 16)),
      actionsPadding: const EdgeInsets.fromLTRB(16, 0, 16, 16),
      actions: [
        Row(
          children: [
            Expanded(
              child: OutlinedButton(onPressed: () => Navigator.pop(c, false), child: Text(no)),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: FilledButton(
                style: FilledButton.styleFrom(backgroundColor: danger ? AppColors.red : AppColors.green),
                onPressed: () => Navigator.pop(c, true),
                child: Text(yes),
              ),
            ),
          ],
        ),
      ],
    ),
  );
  return r == true;
}

void toast(BuildContext context, String message, {bool error = false}) {
  ScaffoldMessenger.of(context)
    ..hideCurrentSnackBar()
    ..showSnackBar(
      SnackBar(
        content: Text(message, style: const TextStyle(fontWeight: FontWeight.w600)),
        backgroundColor: error ? AppColors.redDark : AppColors.ink,
      ),
    );
}

/// Small red/amber strip at the top when the live connection is down.
class ConnectionStrip extends StatelessWidget {
  const ConnectionStrip({super.key, required this.online, required this.label});
  final bool online;
  final String label;

  @override
  Widget build(BuildContext context) {
    return AnimatedSize(
      duration: const Duration(milliseconds: 250),
      child: online
          ? const SizedBox(width: double.infinity)
          : Container(
              width: double.infinity,
              color: AppColors.amber,
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 6),
              child: Row(
                children: [
                  const Icon(Icons.wifi_off_rounded, size: 16, color: Colors.black87),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(
                      label,
                      style: const TextStyle(color: Colors.black87, fontWeight: FontWeight.w700, fontSize: 13),
                    ),
                  ),
                ],
              ),
            ),
    );
  }
}

/// Closes the page that [context] belongs to, even when another page is on top of it
/// (Navigator.pop would close the top page instead).
void closeOwnPage(BuildContext context) {
  final route = ModalRoute.of(context);
  if (route == null || !route.isActive) return;
  final nav = Navigator.of(context);
  if (route.isCurrent) {
    nav.pop();
  } else {
    nav.removeRoute(route);
  }
}
