/// Software estimate of airbag deployment (phone only).
///
/// A real airbag signal needs car-side hardware. The phone can only guess from what it measured:
///   - a very hard hit (peak G), and
///   - a sudden drop in speed.
///
/// The answer is a hint for the responder and the dashboard: NONE / POSSIBLE / LIKELY.
/// It is never "confirmed". Texts must say "airbag likely deployed", not "airbag deployed".
class AirbagHint {
  AirbagHint._();

  /// [peakG] is the hardest hit in g. [speedBeforeKmh] / [speedAfterKmh] are GPS speeds in km/h
  /// before the hit and just after it.
  static Estimate estimate({required double peakG, required double speedBeforeKmh, required double speedAfterKmh}) {
    final drop = speedBeforeKmh - speedAfterKmh;
    if (peakG >= 6.0 && drop >= 25) return Estimate.likely;
    if (peakG >= 4.0 && drop >= 15) return Estimate.possible;
    return Estimate.none;
  }
}

enum Estimate {
  none,
  possible,
  likely;

  /// The word sent to the server (in the crash's sensorData).
  String get code => switch (this) {
    Estimate.none => 'NONE',
    Estimate.possible => 'POSSIBLE',
    Estimate.likely => 'LIKELY',
  };

  String get label => switch (this) {
    Estimate.none => 'No airbag signal',
    Estimate.possible => 'Airbag possibly deployed',
    Estimate.likely => 'Airbag likely deployed',
  };
}
