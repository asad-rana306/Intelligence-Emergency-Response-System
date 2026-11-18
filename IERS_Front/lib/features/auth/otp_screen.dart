import 'dart:async';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../core/net/api_client.dart';
import '../../core/net/api_exception.dart';
import '../../core/theme/app_theme.dart';
import '../../core/widgets/common.dart';

/// Shown after the user fills the sign-up form.
/// Returns true if the phone was verified (so register can proceed).
///
/// Usage:
///   final ok = await Navigator.of(context).push<bool>(
///     MaterialPageRoute(builder: (_) => OtpScreen(phone: _phone.text)),
///   );
///   if (ok == true) { ... continue with register ... }
class OtpScreen extends StatefulWidget {
  const OtpScreen({super.key, required this.phone});
  final String phone;

  @override
  State<OtpScreen> createState() => _OtpScreenState();
}

class _OtpScreenState extends State<OtpScreen> {
  final _code = TextEditingController();
  bool _busy = false;
  String? _error;
  int _coolDown = 0;
  int? _validMinutes; // how long the code works (the server tells us)
  Timer? _tick;

  @override
  void initState() {
    super.initState();
    _send(initial: true);
  }

  @override
  void dispose() {
    _tick?.cancel();
    _code.dispose();
    super.dispose();
  }

  Future<void> _send({bool initial = false}) async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      // The answer: {status, expiresInSeconds, resendAfterSeconds}.
      final reply = await ApiClient.I.postOpen('/auth/otp/send', {'phone': widget.phone});
      var wait = 30;
      if (reply is Map) {
        wait = (reply['resendAfterSeconds'] as num?)?.toInt() ?? wait;
        final expires = (reply['expiresInSeconds'] as num?)?.toInt();
        if (expires != null && expires > 0) _validMinutes = (expires / 60).ceil();
      }
      _startCoolDown(wait);
      if (!initial && mounted) toast(context, 'Code sent again');
    } on ApiException catch (e) {
      if (e.isTooManyRequests) {
        // Asked too often: the server says how long to wait.
        _startCoolDown(e.retryAfter ?? 60);
        _error = 'Please wait a moment before asking for a new code.';
      } else if (e.status == 502) {
        _error = 'The SMS could not be sent right now. Try again in a minute.';
      } else if (e.status == 400) {
        _error = 'This phone number is not valid. Go back and check it (for example 0300 1234567).';
      } else {
        _error = e.message;
      }
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  void _startCoolDown(int seconds) {
    _coolDown = seconds;
    _tick?.cancel();
    _tick = Timer.periodic(const Duration(seconds: 1), (t) {
      if (!mounted) {
        t.cancel();
        return;
      }
      setState(() => _coolDown--);
      if (_coolDown <= 0) t.cancel();
    });
  }

  Future<void> _verify() async {
    final code = _code.text.trim();
    if (code.length != 6) {
      setState(() => _error = 'Enter the 6-digit code');
      return;
    }
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      // The answer is always 200: {ok: true} or {ok: false, reason, remainingAttempts}.
      final reply = await ApiClient.I.postOpen('/auth/otp/verify', {'phone': widget.phone, 'code': code});
      final ok = reply is Map && reply['ok'] == true;
      if (!mounted) return;
      if (ok) {
        Navigator.of(context).pop(true);
      } else {
        final reason = reply is Map ? reply['reason']?.toString() : null;
        final left = reply is Map ? (reply['remainingAttempts'] as num?)?.toInt() : null;
        setState(() => _error = otpVerifyMessage(reason, left));
        if (reason == 'WRONG_CODE') _code.clear();
      }
    } on ApiException catch (e) {
      setState(() => _error = e.message);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('Verify your phone')),
      body: SafeArea(
        child: ListView(
          padding: const EdgeInsets.all(24),
          children: [
            const SizedBox(height: 20),
            const Icon(Icons.sms_rounded, size: 72, color: AppColors.red),
            const SizedBox(height: 16),
            Text(
              'We sent a 6-digit code to ${widget.phone}.',
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 16),
            ),
            const SizedBox(height: 28),
            TextField(
              controller: _code,
              keyboardType: TextInputType.number,
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 28, letterSpacing: 8, fontWeight: FontWeight.w800),
              maxLength: 6,
              inputFormatters: [FilteringTextInputFormatter.digitsOnly],
              decoration: const InputDecoration(
                counterText: '',
                hintText: '••••••',
              ),
            ),
            if (_validMinutes != null && _error == null) ...[
              const SizedBox(height: 8),
              Text(
                'The code works for $_validMinutes minutes.',
                textAlign: TextAlign.center,
                style: const TextStyle(color: AppColors.muted),
              ),
            ],
            if (_error != null) ...[
              const SizedBox(height: 8),
              Text(_error!, textAlign: TextAlign.center, style: const TextStyle(color: AppColors.red)),
            ],
            const SizedBox(height: 16),
            FilledButton(
              onPressed: _busy ? null : _verify,
              child: _busy
                  ? const SizedBox(
                      height: 20, width: 20,
                      child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white))
                  : const Text('Verify'),
            ),
            const SizedBox(height: 8),
            TextButton(
              onPressed: (_busy || _coolDown > 0) ? null : _send,
              child: Text(_coolDown > 0 ? 'Resend in $_coolDown s' : 'Resend code'),
            ),
          ],
        ),
      ),
    );
  }
}

/// What to tell the user when the server says the code is not good.
String otpVerifyMessage(String? reason, int? remainingAttempts) {
  switch (reason) {
    case 'WRONG_CODE':
      if (remainingAttempts == null) return 'Wrong code. Check the SMS and try again.';
      if (remainingAttempts <= 0) return 'Wrong code. Tap "Resend code" to get a new one.';
      return 'Wrong code. $remainingAttempts ${remainingAttempts == 1 ? 'try' : 'tries'} left.';
    case 'EXPIRED':
      return 'This code is too old. Tap "Resend code" to get a new one.';
    case 'TOO_MANY_ATTEMPTS':
      return 'Too many wrong tries. Tap "Resend code" to get a new code.';
    default:
      return 'Wrong or expired code. Check the SMS, or tap "Resend code".';
  }
}
