package com.iers.auth.service;

import com.iers.auth.dto.request.EmergencyContactRequest;
import com.iers.auth.dto.response.EmergencyContactResponse;
import com.iers.auth.entity.EmergencyContact;
import com.iers.auth.entity.User;
import com.iers.auth.entity.enums.Role;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.exception.UnauthorizedException;
import com.iers.auth.repository.EmergencyContactRepository;
import com.iers.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class EmergencyContactServiceTest {

    @Mock private EmergencyContactRepository contactRepository;
    @Mock private UserRepository userRepository;

    @InjectMocks private EmergencyContactService service;

    private User testUser;
    private final UUID userId = UUID.randomUUID();
    private final UUID contactId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(userId).email("driver@test.com")
                .fullName("Test Driver").role(Role.DRIVER).build();
    }

    @Test
    @DisplayName("addContact — success")
    void addContact_success() {
        EmergencyContactRequest request = EmergencyContactRequest.builder()
                .contactName("Mom").phone("+19876543210").relationship("Mother").build();

        EmergencyContact saved = EmergencyContact.builder()
                .id(contactId).user(testUser)
                .contactName("Mom").phone("+19876543210").relationship("Mother").build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(contactRepository.save(any())).thenReturn(saved);

        EmergencyContactResponse response = service.addContact(userId, request);

        assertThat(response.getContactName()).isEqualTo("Mom");
        assertThat(response.getRelationship()).isEqualTo("Mother");
    }

    @Test
    @DisplayName("addContact — throws when user not found")
    void addContact_userNotFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.addContact(userId, new EmergencyContactRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("getContactsByUserId — returns list")
    void getContacts_returnsList() {
        List<EmergencyContact> contacts = List.of(
                EmergencyContact.builder().id(UUID.randomUUID()).user(testUser)
                        .contactName("Mom").phone("111").relationship("Mother").build(),
                EmergencyContact.builder().id(UUID.randomUUID()).user(testUser)
                        .contactName("Dad").phone("222").relationship("Father").build()
        );

        when(contactRepository.findAllByUserId(userId)).thenReturn(contacts);

        List<EmergencyContactResponse> result = service.getContactsByUserId(userId);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContactName()).isEqualTo("Mom");
    }

    @Test
    @DisplayName("getContactsByUserId — returns empty list when none exist")
    void getContacts_empty() {
        when(contactRepository.findAllByUserId(userId)).thenReturn(List.of());

        List<EmergencyContactResponse> result = service.getContactsByUserId(userId);

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("deleteContact — success when owner matches")
    void deleteContact_success() {
        EmergencyContact contact = EmergencyContact.builder()
                .id(contactId).user(testUser).contactName("Mom").phone("111").build();

        when(contactRepository.findById(contactId)).thenReturn(Optional.of(contact));

        service.deleteContact(contactId, userId);

        verify(contactRepository).delete(contact);
    }

    @Test
    @DisplayName("deleteContact — throws when contact not found")
    void deleteContact_notFound() {
        when(contactRepository.findById(contactId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.deleteContact(contactId, userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("deleteContact — throws UnauthorizedException when not owner")
    void deleteContact_notOwner() {
        UUID otherUserId = UUID.randomUUID();
        EmergencyContact contact = EmergencyContact.builder()
                .id(contactId).user(testUser).contactName("Mom").phone("111").build();

        when(contactRepository.findById(contactId)).thenReturn(Optional.of(contact));

        assertThatThrownBy(() -> service.deleteContact(contactId, otherUserId))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("only delete your own");
    }
}
