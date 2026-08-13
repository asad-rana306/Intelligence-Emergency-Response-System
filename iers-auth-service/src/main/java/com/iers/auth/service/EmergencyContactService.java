package com.iers.auth.service;

import com.iers.auth.dto.request.EmergencyContactRequest;
import com.iers.auth.dto.response.EmergencyContactResponse;
import com.iers.auth.entity.EmergencyContact;
import com.iers.auth.entity.User;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.exception.UnauthorizedException;
import com.iers.auth.repository.EmergencyContactRepository;
import com.iers.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class EmergencyContactService {

    private final EmergencyContactRepository contactRepository;
    private final UserRepository userRepository;

    @Transactional
    public EmergencyContactResponse addContact(UUID userId, EmergencyContactRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        EmergencyContact contact = EmergencyContact.builder()
                .user(user)
                .contactName(request.getContactName())
                .phone(request.getPhone())
                .relationship(request.getRelationship())
                .build();

        contact = contactRepository.save(contact);
        return EmergencyContactResponse.from(contact);
    }

    public List<EmergencyContactResponse> getContactsByUserId(UUID userId) {
        return contactRepository.findAllByUserId(userId).stream()
                .map(EmergencyContactResponse::from)
                .toList();
    }

    @Transactional
    public void deleteContact(UUID contactId, UUID userId) {
        EmergencyContact contact = contactRepository.findById(contactId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Emergency contact not found: " + contactId));

        // Ownership check: only the owner can delete their contacts
        if (!contact.getUser().getId().equals(userId)) {
            throw new UnauthorizedException("You can only delete your own emergency contacts");
        }

        contactRepository.delete(contact);
    }
}
