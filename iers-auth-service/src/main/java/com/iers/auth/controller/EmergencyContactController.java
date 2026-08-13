package com.iers.auth.controller;

import com.iers.auth.dto.request.EmergencyContactRequest;
import com.iers.auth.dto.response.EmergencyContactResponse;
import com.iers.auth.service.EmergencyContactService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/emergency-contacts")
@RequiredArgsConstructor
public class EmergencyContactController {

    private final EmergencyContactService contactService;

    @PostMapping
    public ResponseEntity<EmergencyContactResponse> add(
            @RequestHeader("X-User-Id") String userId,
            @Valid @RequestBody EmergencyContactRequest request) {
        EmergencyContactResponse response =
                contactService.addContact(UUID.fromString(userId), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/me")
    public ResponseEntity<List<EmergencyContactResponse>> getMyContacts(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(
                contactService.getContactsByUserId(UUID.fromString(userId)));
    }

    @DeleteMapping("/{contactId}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-User-Id") String userId,
            @PathVariable UUID contactId) {
        contactService.deleteContact(contactId, UUID.fromString(userId));
        return ResponseEntity.noContent().build();
    }
}
