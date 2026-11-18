/// Plain data types that match the API contract. Unknown fields are ignored (contract rule).
library;

import 'dart:typed_data';

String? _s(Object? v) => v?.toString();
double? _d(Object? v) => v is num ? v.toDouble() : double.tryParse('${v ?? ''}');
int? _i(Object? v) => v is num ? v.toInt() : int.tryParse('${v ?? ''}');
DateTime? _t(Object? v) => v == null ? null : DateTime.tryParse(v.toString())?.toLocal();
Map<String, dynamic> _m(Object? v) => v is Map ? Map<String, dynamic>.from(v) : <String, dynamic>{};

/// One message on /user/queue/events: {type, eventId, serverTime, data}.
class LiveEvent {
  final String type;
  final String? eventId;
  final DateTime? serverTime;
  final Map<String, dynamic> data;

  LiveEvent(this.type, this.eventId, this.serverTime, this.data);

  factory LiveEvent.fromJson(Map<String, dynamic> j) =>
      LiveEvent(_s(j['type']) ?? '', _s(j['eventId']), _t(j['serverTime']), _m(j['data']));

  String? get incidentId => _s(data['incidentId']);
  String? get crashEventId => _s(data['crashEventId']);
}

/// One message on /user/queue/call: {incidentId, from, kind, payload}.
class CallSignal {
  final String incidentId;
  final String? from;
  final String kind;
  final Map<String, dynamic> payload;

  CallSignal(this.incidentId, this.from, this.kind, this.payload);

  factory CallSignal.fromJson(Map<String, dynamic> j) =>
      CallSignal(_s(j['incidentId']) ?? '', _s(j['from']), _s(j['kind']) ?? '', _m(j['payload']));
}

class ResponderInfo {
  final String? name;
  final String? phone;
  final String? vehicleId;
  const ResponderInfo({this.name, this.phone, this.vehicleId});

  factory ResponderInfo.fromJson(Object? v) {
    final j = _m(v);
    return ResponderInfo(name: _s(j['name']), phone: _s(j['phone']), vehicleId: _s(j['vehicleId']));
  }

  bool get isEmpty => (name ?? '').isEmpty && (phone ?? '').isEmpty;
}

/// The incident as the REST lookups return it.
class Incident {
  final String id;
  final String? crashEventId;
  final String? driverId;
  final String? driverName;
  final String? driverPhone;
  final int? priorityScore;
  final double? lat;
  final double? lng;
  final String status;
  final String? assignedResponderId;
  final ResponderInfo? responder;
  final String? bloodType;
  final String? allergies;
  final bool bystanderAssisting;
  final DateTime? createdAt;

  Incident({
    required this.id,
    required this.status,
    this.crashEventId,
    this.driverId,
    this.driverName,
    this.driverPhone,
    this.priorityScore,
    this.lat,
    this.lng,
    this.assignedResponderId,
    this.responder,
    this.bloodType,
    this.allergies,
    this.bystanderAssisting = false,
    this.createdAt,
  });

  factory Incident.fromJson(Map<String, dynamic> j) => Incident(
    id: _s(j['id'] ?? j['incidentId']) ?? '',
    status: _s(j['status']) ?? 'DISPATCHING',
    crashEventId: _s(j['crashEventId']),
    driverId: _s(j['driverId']),
    driverName: _s(j['driverName']),
    driverPhone: _s(j['driverPhone']),
    priorityScore: _i(j['priorityScore']),
    lat: _d(j['gpsLat']),
    lng: _d(j['gpsLng']),
    assignedResponderId: _s(j['assignedResponderId']),
    responder: j['responder'] == null ? null : ResponderInfo.fromJson(j['responder']),
    bloodType: _s(j['bloodType']),
    allergies: _s(j['allergies']),
    bystanderAssisting: j['bystanderAssisting'] == true,
    createdAt: _t(j['createdAt']),
  );
}

/// An offer to a responder (INCIDENT_OFFERED data, or GET /api/dispatch/offers/current).
class Offer {
  final String incidentId;
  final String? driverName;
  final double? lat;
  final double? lng;
  final int? priorityScore;
  final String? bloodType;
  final String? allergies;
  final int attempt;
  final DateTime receivedAt;

  Offer({
    required this.incidentId,
    this.driverName,
    this.lat,
    this.lng,
    this.priorityScore,
    this.bloodType,
    this.allergies,
    this.attempt = 1,
    DateTime? receivedAt,
  }) : receivedAt = receivedAt ?? DateTime.now();

  factory Offer.fromJson(Map<String, dynamic> j) => Offer(
    incidentId: _s(j['incidentId'] ?? j['id']) ?? '',
    driverName: _s(j['driverName']),
    lat: _d(j['gpsLat']),
    lng: _d(j['gpsLng']),
    priorityScore: _i(j['priorityScore']),
    bloodType: _s(j['bloodType']),
    allergies: _s(j['allergies']),
    attempt: _i(j['attempt']) ?? 1,
  );
}

/// GET /api/dispatch/history row.
class HistoryItem {
  final String incidentId;
  final String status;
  final String? myResponse;
  final DateTime? offeredAt;
  final String? driverName;
  final double? lat;
  final double? lng;
  final int? priorityScore;
  final bool assignedToMe;
  final bool canAcceptAgain;

  HistoryItem({
    required this.incidentId,
    required this.status,
    this.myResponse,
    this.offeredAt,
    this.driverName,
    this.lat,
    this.lng,
    this.priorityScore,
    this.assignedToMe = false,
    this.canAcceptAgain = false,
  });

  factory HistoryItem.fromJson(Map<String, dynamic> j) => HistoryItem(
    incidentId: _s(j['incidentId']) ?? '',
    status: _s(j['status']) ?? '',
    myResponse: _s(j['myResponse']),
    offeredAt: _t(j['offeredAt'] ?? j['createdAt']),
    driverName: _s(j['driverName']),
    lat: _d(j['gpsLat']),
    lng: _d(j['gpsLng']),
    priorityScore: _i(j['priorityScore']),
    assignedToMe: j['assignedToMe'] == true,
    canAcceptAgain: j['canAcceptAgain'] == true,
  );
}

enum MessageState { sending, sent, failed }

/// One chat message (server + the phone's own unsent ones).
class ChatMessage {
  String? messageId;
  final String incidentId;
  final String clientMessageId;
  final String senderId;
  final String senderRole; // DRIVER / RESPONDER
  final String kind; // TEXT / VOICE
  final String? text;
  final int? durationMs;
  String? audioUrl;
  String? localAudioPath;
  Uint8List? localAudioBytes; // kept in memory so a voice note plays on web too
  String? localAudioMime; // content type for upload (audio/mp4 on phone, audio/webm on web)
  final DateTime createdAt;
  MessageState state;

  /// Why it can never be sent (e.g. "Chat is open only while a responder is on the case").
  String? error;

  ChatMessage({
    this.messageId,
    required this.incidentId,
    required this.clientMessageId,
    required this.senderId,
    required this.senderRole,
    required this.kind,
    this.text,
    this.durationMs,
    this.audioUrl,
    this.localAudioPath,
    required this.createdAt,
    this.state = MessageState.sent,
  });

  bool get isVoice => kind == 'VOICE';

  factory ChatMessage.fromJson(Map<String, dynamic> j) => ChatMessage(
    messageId: _s(j['messageId']),
    incidentId: _s(j['incidentId']) ?? '',
    clientMessageId: _s(j['clientMessageId']) ?? _s(j['messageId']) ?? '',
    senderId: _s(j['senderId']) ?? '',
    senderRole: _s(j['senderRole']) ?? '',
    kind: _s(j['kind']) ?? 'TEXT',
    text: _s(j['text']),
    durationMs: _i(j['durationMs']),
    audioUrl: _s(j['audioUrl']),
    createdAt: _t(j['createdAt']) ?? DateTime.now(),
  );
}

class EmergencyContact {
  final String? id;
  final String name;
  final String phone;
  final String? relationship;
  EmergencyContact({this.id, required this.name, required this.phone, this.relationship});

  factory EmergencyContact.fromJson(Map<String, dynamic> j) => EmergencyContact(
    id: _s(j['id']),
    name: _s(j['contactName'] ?? j['name']) ?? 'Contact',
    phone: _s(j['phone']) ?? '',
    relationship: _s(j['relationship']),
  );
}

class MedicalProfile {
  final String? bloodType;
  final String? allergies;
  final String? medications;
  final String? chronicConditions;
  final String? emergencyNotes;
  const MedicalProfile({this.bloodType, this.allergies, this.medications, this.chronicConditions, this.emergencyNotes});

  factory MedicalProfile.fromJson(Map<String, dynamic> j) => MedicalProfile(
    bloodType: _s(j['bloodType']),
    allergies: _s(j['allergies']),
    medications: _s(j['medications']),
    chronicConditions: _s(j['chronicConditions']),
    emergencyNotes: _s(j['emergencyNotes']),
  );

  Map<String, dynamic> toJson() => {
    'bloodType': bloodType,
    'allergies': allergies,
    'medications': medications,
    'chronicConditions': chronicConditions,
    'emergencyNotes': emergencyNotes,
  };

  bool get isEmpty => [
    bloodType,
    allergies,
    medications,
    chronicConditions,
    emergencyNotes,
  ].every((v) => v == null || v.trim().isEmpty);
}

/// Priority score (1–5 from the ML model) as words and a level 0..3.
class Priority {
  static String label(int? score) {
    if (score == null) return 'EMERGENCY';
    if (score >= 5) return 'CRITICAL';
    if (score == 4) return 'SERIOUS';
    if (score == 3) return 'MODERATE';
    return 'MINOR';
  }

  static int level(int? score) {
    if (score == null) return 2;
    if (score >= 5) return 3;
    if (score == 4) return 2;
    if (score == 3) return 1;
    return 0;
  }
}
