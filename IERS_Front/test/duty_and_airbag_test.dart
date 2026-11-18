import 'package:flutter_test/flutter_test.dart';
import 'package:iers_app/features/auth/otp_screen.dart';
import 'package:iers_app/services/airbag_hint.dart';
import 'package:iers_app/services/crash_detector.dart';
import 'package:iers_app/services/duty_service.dart';
import 'package:iers_app/services/local_alerts.dart';

void main() {
  group('duty time text', () {
    test('formatLeft', () {
      expect(DutyService.formatLeft(0), '—');
      expect(DutyService.formatLeft(125), '2m 05s');
      expect(DutyService.formatLeft(3725), '1h 02m');
    });

    test('formatMinutes rounds up (the permanent notification)', () {
      expect(DutyService.formatMinutes(0), 'less than a minute');
      expect(DutyService.formatMinutes(20), '1 min');
      expect(DutyService.formatMinutes(61), '2 min');
      expect(DutyService.formatMinutes(3600), '60 min');
    });

    test('parseTime reads what the server sends', () {
      expect(DutyService.parseTime(null), isNull);
      expect(DutyService.parseTime('2026-11-18T10:34:00.123456789Z'), isNotNull);
      expect(DutyService.parseTime(1763462040)!.year, 2025); // epoch seconds
      expect(DutyService.parseTime(1763462040000)!.year, 2025); // epoch milliseconds
    });
  });

  group('DutyService follows the server', () {
    setUp(() => DutyService.I.reset());

    test('a profile answer sets status and seconds left', () {
      DutyService.I.apply({'dutyStatus': 'ON_DUTY', 'secondsRemaining': 3000, 'dutyStartedAt': '2026-11-18T10:00:00Z'});
      expect(DutyService.I.status, 'ON_DUTY');
      expect(DutyService.I.isActive, isTrue);
      expect(DutyService.I.secondsLeft, inInclusiveRange(2995, 3000));
    });

    test('OFF duty clears the shift', () {
      DutyService.I.apply({'dutyStatus': 'ON_DUTY', 'secondsRemaining': 3000});
      DutyService.I.apply({'dutyStatus': 'OFF_DUTY', 'secondsRemaining': null});
      expect(DutyService.I.status, 'OFF_DUTY');
      expect(DutyService.I.secondsLeft, 0);
      expect(DutyService.I.wasAutoOffDuty, isFalse); // the heartbeat was not running here
    });

    test('an answer without a status is ignored', () {
      DutyService.I.apply({'dutyStatus': 'ON_DUTY', 'secondsRemaining': 100});
      DutyService.I.apply({'something': 'else'});
      expect(DutyService.I.status, 'ON_DUTY');
    });

    test('"server put me OFF duty" is flagged once, a repeat within 90 s is ignored', () {
      DutyService.I.serverPutOffDuty('MAX_DURATION');
      expect(DutyService.I.wasAutoOffDuty, isTrue);
      expect(DutyService.I.autoOffReason, 'MAX_DURATION');

      DutyService.I.ackAutoOffDuty();
      DutyService.I.serverPutOffDuty('MAX_DURATION'); // the same event from push + service
      expect(DutyService.I.wasAutoOffDuty, isFalse);
    });
  });

  group('airbag hint (a guess, never a fact)', () {
    test('hard hit and a big drop in speed = likely', () {
      expect(AirbagHint.estimate(peakG: 8, speedBeforeKmh: 60, speedAfterKmh: 10), Estimate.likely);
    });

    test('medium hit = possible', () {
      expect(AirbagHint.estimate(peakG: 5, speedBeforeKmh: 50, speedAfterKmh: 30), Estimate.possible);
    });

    test('a hard jolt without a drop in speed = none', () {
      expect(AirbagHint.estimate(peakG: 12, speedBeforeKmh: 40, speedAfterKmh: 38), Estimate.none);
    });

    test('codes sent to the server', () {
      expect(Estimate.none.code, 'NONE');
      expect(Estimate.possible.code, 'POSSIBLE');
      expect(Estimate.likely.code, 'LIKELY');
    });
  });

  group('crash request body', () {
    test('has the fields the telemetry service reads', () {
      final r = CrashReading(8, 20, 60, 31.5, 74.3, {'airbagEstimate': 'LIKELY'}, 'IMPACT');
      final body = r.toRequest(driverName: 'Ali', driverPhone: '+923001234567');
      expect(body['gForce'], 8);
      expect(body['speed'], 60);
      expect(body['gpsLat'], 31.5);
      expect(body['manual'], false);
      expect(body['driverName'], 'Ali');
      expect((body['sensorData'] as Map)['airbagEstimate'], 'LIKELY');
    });

    test('a better GPS fix can replace the one in the reading', () {
      final r = CrashReading(8, 20, 60, 0, 0, {}, 'SOS');
      final body = r.toRequest(lat: 30.1, lng: 70.2, manual: true);
      expect(body['gpsLat'], 30.1);
      expect(body['gpsLng'], 70.2);
      expect(body['manual'], true);
    });
  });

  group('words for the user', () {
    test('OFF-duty notification text depends on the reason', () {
      expect(LocalAlerts.offDutyText('MAX_DURATION'), contains('1-hour shift'));
      expect(LocalAlerts.offDutyText('HEARTBEAT_LOST'), contains('lost contact'));
    });

    test('OTP verify messages', () {
      expect(otpVerifyMessage('WRONG_CODE', 2), contains('2 tries left'));
      expect(otpVerifyMessage('WRONG_CODE', 1), contains('1 try left'));
      expect(otpVerifyMessage('WRONG_CODE', 0), contains('Resend'));
      expect(otpVerifyMessage('EXPIRED', null), contains('too old'));
      expect(otpVerifyMessage('TOO_MANY_ATTEMPTS', null), contains('Too many'));
      expect(otpVerifyMessage(null, null), contains('Wrong or expired'));
    });
  });
}
