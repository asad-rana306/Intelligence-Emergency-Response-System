package com.iers.iot.exception;

import com.iers.iot.dto.ErrorResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CrashEventNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(CrashEventNotFoundException ex, WebRequest req) {
        return build(HttpStatus.NOT_FOUND, ex.getMessage(), req);
    }

    // NEW (Phase 2): someone else's crash
    @ExceptionHandler(CrashAccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleDenied(CrashAccessDeniedException ex, WebRequest req) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), req);
    }

    // NEW (Phase 2): a bad ID such as "1" is a 400, not a server error
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleBadParam(MethodArgumentTypeMismatchException ex, WebRequest req) {
        String expected = UUID.class.equals(ex.getRequiredType()) ? "a UUID"
                : ex.getRequiredType() != null ? ex.getRequiredType().getSimpleName() : "a valid value";
        String msg = "Invalid value '" + ex.getValue() + "' for '" + ex.getName() + "'. Expected " + expected;
        return build(HttpStatus.BAD_REQUEST, msg, req);
    }

    // NEW (Phase 2)
    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex, WebRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Missing header: " + ex.getHeaderName(), req);
    }

    // NEW (Phase 2): broken JSON from the firmware is a 400, not a 500
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex, WebRequest req) {
        return build(HttpStatus.BAD_REQUEST, "Request body is missing or is not valid JSON", req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex, WebRequest req) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining(", "));
        return build(HttpStatus.BAD_REQUEST, msg, req);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleBadArg(IllegalArgumentException ex, WebRequest req) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, WebRequest req) {
        log.error("Unhandled exception", ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", req);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus s, String msg, WebRequest req) {
        return ResponseEntity.status(s).body(ErrorResponse.builder()
                .status(s.value()).error(s.getReasonPhrase()).message(msg)
                .path(req.getDescription(false).replace("uri=", "")).build());
    }
}
