import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

/// A round button you must HOLD (so a pocket or a child cannot press it by mistake).
/// The ring fills while you hold; when full, [onConfirmed] runs.
class HoldButton extends StatefulWidget {
  const HoldButton({
    super.key,
    required this.onConfirmed,
    this.size = 190,
    this.color = const Color(0xFFE5322D),
    this.hold = const Duration(milliseconds: 1800),
    this.title = 'SOS',
    this.subtitle = 'Hold for help',
  });

  final VoidCallback onConfirmed;
  final double size;
  final Color color;
  final Duration hold;
  final String title;
  final String subtitle;

  @override
  State<HoldButton> createState() => _HoldButtonState();
}

class _HoldButtonState extends State<HoldButton> with SingleTickerProviderStateMixin {
  late final AnimationController _c = AnimationController(vsync: this, duration: widget.hold)
    ..addStatusListener((s) {
      if (s == AnimationStatus.completed) {
        HapticFeedback.heavyImpact();
        widget.onConfirmed();
        _c.reset();
      }
    });

  @override
  void dispose() {
    _c.dispose();
    super.dispose();
  }

  void _down() {
    HapticFeedback.selectionClick();
    _c.forward();
  }

  void _up() {
    if (_c.status != AnimationStatus.completed) _c.reverse();
  }

  @override
  Widget build(BuildContext context) {
    final s = widget.size;
    return Semantics(
      button: true,
      label: '${widget.title}. ${widget.subtitle}',
      child: GestureDetector(
        onTapDown: (_) => _down(),
        onTapUp: (_) => _up(),
        onTapCancel: _up,
        child: AnimatedBuilder(
          animation: _c,
          builder: (context, _) => SizedBox(
            width: s,
            height: s,
            child: Stack(
              alignment: Alignment.center,
              children: [
                Container(
                  width: s,
                  height: s,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    color: widget.color.withValues(alpha: 0.12 + 0.18 * _c.value),
                  ),
                ),
                SizedBox(
                  width: s - 14,
                  height: s - 14,
                  child: CircularProgressIndicator(
                    value: _c.value,
                    strokeWidth: 8,
                    color: Colors.white,
                    backgroundColor: Colors.transparent,
                  ),
                ),
                Container(
                  width: s - 30,
                  height: s - 30,
                  decoration: BoxDecoration(
                    shape: BoxShape.circle,
                    gradient: LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [widget.color, Color.lerp(widget.color, Colors.black, 0.25)!],
                    ),
                    boxShadow: [
                      BoxShadow(
                        color: widget.color.withValues(alpha: 0.45),
                        blurRadius: 24,
                        offset: const Offset(0, 10),
                      ),
                    ],
                  ),
                  child: Column(
                    mainAxisAlignment: MainAxisAlignment.center,
                    children: [
                      Text(
                        widget.title,
                        style: TextStyle(
                          color: Colors.white,
                          fontSize: s * 0.2,
                          fontWeight: FontWeight.w900,
                          letterSpacing: 2,
                        ),
                      ),
                      const SizedBox(height: 2),
                      Text(
                        _c.value > 0 ? 'Keep holding…' : widget.subtitle,
                        style: const TextStyle(color: Colors.white70, fontSize: 13, fontWeight: FontWeight.w600),
                      ),
                    ],
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
