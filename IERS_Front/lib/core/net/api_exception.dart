/// One error type for every server call. [status] 0 = no connection.
class ApiException implements Exception {
  final int status;
  final String message;

  /// Seconds to wait before trying again (from the server's Retry-After header, on 429).
  final int? retryAfter;
  const ApiException(this.status, this.message, {this.retryAfter});

  bool get isOffline => status == 0;
  bool get isUnauthorized => status == 401;
  bool get isForbidden => status == 403;
  bool get isNotFound => status == 404;
  bool get isConflict => status == 409;
  bool get isTooManyRequests => status == 429;

  @override
  String toString() => message;
}
