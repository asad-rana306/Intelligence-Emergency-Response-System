package com.iers.auth.service;

import com.iers.auth.dto.request.UpdateUserRequest;
import com.iers.auth.dto.response.UserResponse;
import com.iers.auth.entity.User;
import com.iers.auth.entity.enums.Role;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @InjectMocks private UserService service;

    private User testUser;
    private final UUID userId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        testUser = User.builder()
                .id(userId).email("user@test.com")
                .fullName("Original Name").phone("+1111111111")
                .role(Role.DRIVER).build();
    }

    @Test
    @DisplayName("getUserById — success")
    void getUserById_success() {
        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));

        UserResponse response = service.getUserById(userId);

        assertThat(response.getEmail()).isEqualTo("user@test.com");
        assertThat(response.getFullName()).isEqualTo("Original Name");
    }

    @Test
    @DisplayName("getUserById — throws when not found")
    void getUserById_notFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getUserById(userId))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateUser — partial update only changes provided fields")
    void updateUser_partialUpdate() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .fullName("Updated Name").build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = service.updateUser(userId, request);

        assertThat(response.getFullName()).isEqualTo("Updated Name");
        assertThat(response.getPhone()).isEqualTo("+1111111111"); // unchanged
    }

    @Test
    @DisplayName("updateUser — updates all provided fields")
    void updateUser_fullUpdate() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .fullName("New Name").phone("+9999999999").deviceId("device-xyz").build();

        when(userRepository.findById(userId)).thenReturn(Optional.of(testUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UserResponse response = service.updateUser(userId, request);

        assertThat(response.getFullName()).isEqualTo("New Name");
        assertThat(response.getPhone()).isEqualTo("+9999999999");
        assertThat(testUser.getDeviceId()).isEqualTo("device-xyz");
    }

    @Test
    @DisplayName("updateUser — throws when user not found")
    void updateUser_notFound() {
        when(userRepository.findById(userId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateUser(userId, new UpdateUserRequest()))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
