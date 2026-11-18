/// Public emergency numbers (Pakistan). Shown on the driver's home and used as the last fallback.
class EmergencyNumber {
  final String label;
  final String number;
  final String hint;
  const EmergencyNumber(this.label, this.number, this.hint);
}

class EmergencyNumbers {
  static const rescue = EmergencyNumber('Rescue', '1122', 'Ambulance & rescue');
  static const police = EmergencyNumber('Police', '15', 'Police');
  static const edhi = EmergencyNumber('Edhi', '115', 'Edhi ambulance');
  static const motorway = EmergencyNumber('Motorway', '130', 'Motorway police');

  static const all = [rescue, police, edhi, motorway];
}
