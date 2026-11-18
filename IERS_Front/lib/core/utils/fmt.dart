import 'package:intl/intl.dart';

class Fmt {
  static String timeAgo(DateTime? t) {
    if (t == null) return '';
    final d = DateTime.now().difference(t);
    if (d.inSeconds < 45) return 'just now';
    if (d.inMinutes < 60) return '${d.inMinutes} min ago';
    if (d.inHours < 24) return '${d.inHours} h ago';
    if (d.inDays < 7) return '${d.inDays} d ago';
    return DateFormat('d MMM').format(t);
  }

  static String clock(DateTime t) => DateFormat('h:mm a').format(t);
  static String dateTime(DateTime t) => DateFormat('d MMM, h:mm a').format(t);

  static String duration(Duration d) {
    final m = d.inMinutes.remainder(60).toString().padLeft(2, '0');
    final s = d.inSeconds.remainder(60).toString().padLeft(2, '0');
    return d.inHours > 0 ? '${d.inHours}:$m:$s' : '$m:$s';
  }

  static String status(String s) {
    switch (s) {
      case 'DISPATCHING':
        return 'Finding a unit';
      case 'DISPATCHED':
        return 'Asking a unit';
      case 'ACCEPTED':
        return 'Unit on the way';
      case 'ARRIVED':
        return 'Unit arrived';
      case 'EN_ROUTE_HOSPITAL':
        return 'Going to hospital';
      case 'RESOLVED':
        return 'Completed';
      case 'CANCELLED_BY_USER':
        return 'Cancelled by driver';
      default:
        return s.replaceAll('_', ' ').toLowerCase();
    }
  }

  static String response(String? r) {
    switch (r) {
      case 'ACCEPTED':
        return 'You accepted';
      case 'REJECTED':
        return 'You rejected';
      case 'TIMED_OUT':
        return 'Missed / moved on';
      case 'PENDING':
        return 'Waiting for you';
      default:
        return r ?? '';
    }
  }

  static String initials(String? name) {
    final parts = (name ?? '').trim().split(RegExp(r'\s+')).where((p) => p.isNotEmpty).toList();
    if (parts.isEmpty) return '?';
    return parts.take(2).map((p) => p[0].toUpperCase()).join();
  }
}
