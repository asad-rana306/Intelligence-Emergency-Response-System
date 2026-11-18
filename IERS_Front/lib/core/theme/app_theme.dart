import 'package:flutter/material.dart';

/// Colours with a meaning. Red = emergency, green = safe / accept, amber = busy / warning.
class AppColors {
  static const red = Color(0xFFE5322D);
  static const redDark = Color(0xFFB3201C);
  static const green = Color(0xFF16A34A);
  static const amber = Color(0xFFF59E0B);
  static const blue = Color(0xFF2563EB);
  static const ink = Color(0xFF111827);
  static const muted = Color(0xFF6B7280);
  static const surface = Colors.white;
  static const card = Colors.white;
  static const cardLine = Color(0xFFE5E7EB);
  static const night = Color(0xFF0B1220);
  static const nightCard = Color(0xFF151E30);

  static Color priority(int level) {
    switch (level) {
      case 3:
        return red;
      case 2:
        return const Color(0xFFEA580C);
      case 1:
        return amber;
      default:
        return blue;
    }
  }
}

class AppTheme {
  static const radius = 20.0;

  static ThemeData light() => _build(Brightness.light);
  static ThemeData dark() => _build(Brightness.dark);

  static ThemeData _build(Brightness b) {
    final dark = b == Brightness.dark;
    final scheme = ColorScheme.fromSeed(
      seedColor: AppColors.red,
      brightness: b,
      primary: AppColors.red,
      onPrimary: Colors.white,
      secondary: AppColors.green,
      surface: dark ? AppColors.nightCard : AppColors.card,
      error: AppColors.redDark,
    );
    final base = ThemeData(useMaterial3: true, colorScheme: scheme, brightness: b);
    final ink = dark ? Colors.white : AppColors.ink;
    return base.copyWith(
      scaffoldBackgroundColor: dark ? AppColors.night : AppColors.surface,
      appBarTheme: AppBarTheme(
        backgroundColor: dark ? AppColors.night : AppColors.surface,
        foregroundColor: ink,
        elevation: 0,
        scrolledUnderElevation: 0,
        centerTitle: false,
        titleTextStyle: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: ink),
      ),
      textTheme: base.textTheme
          .apply(bodyColor: ink, displayColor: ink)
          .copyWith(
            headlineLarge: TextStyle(fontSize: 32, fontWeight: FontWeight.w900, color: ink, height: 1.1),
            headlineMedium: TextStyle(fontSize: 26, fontWeight: FontWeight.w800, color: ink),
            titleLarge: TextStyle(fontSize: 20, fontWeight: FontWeight.w800, color: ink),
            titleMedium: TextStyle(fontSize: 16, fontWeight: FontWeight.w700, color: ink),
          ),
      cardTheme: CardThemeData(
        color: dark ? AppColors.nightCard : AppColors.card,
        elevation: 0,
        margin: EdgeInsets.zero,
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(radius),
          side: BorderSide(color: dark ? Colors.white10 : AppColors.cardLine),
        ),
      ),
      filledButtonTheme: FilledButtonThemeData(
        style: FilledButton.styleFrom(
          minimumSize: const Size.fromHeight(56),
          textStyle: const TextStyle(fontSize: 17, fontWeight: FontWeight.w800),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        ),
      ),
      outlinedButtonTheme: OutlinedButtonThemeData(
        style: OutlinedButton.styleFrom(
          minimumSize: const Size.fromHeight(56),
          textStyle: const TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
          shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16)),
        ),
      ),
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: dark ? AppColors.nightCard : Colors.white,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(14), borderSide: BorderSide.none),
        enabledBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: BorderSide(color: dark ? Colors.white12 : const Color(0xFFE5E7EB)),
        ),
        focusedBorder: OutlineInputBorder(
          borderRadius: BorderRadius.circular(14),
          borderSide: const BorderSide(color: AppColors.red, width: 2),
        ),
      ),
      snackBarTheme: const SnackBarThemeData(behavior: SnackBarBehavior.floating),
      dividerTheme: DividerThemeData(color: dark ? Colors.white10 : const Color(0xFFE5E7EB), space: 1),
    );
  }
}
