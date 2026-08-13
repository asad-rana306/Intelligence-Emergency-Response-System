package com.iers.auth.service;

import com.iers.auth.dto.request.UpdateUserRequest;
import com.iers.auth.dto.response.UserResponse;
import com.iers.auth.entity.User;
import com.iers.auth.exception.ResourceNotFoundException;
import com.iers.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    public UserResponse getUserById(UUID userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));
        return UserResponse.from(user);
    }

    @Transactional
    public UserResponse updateUser(UUID userId, UpdateUserRequest request) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found: " + userId));

        if (request.getFullName() != null) {
            user.setFullName(request.getFullName());
        }
        if (request.getPhone() != null) {
            user.setPhone(request.getPhone());
        }
        if (request.getDeviceId() != null) {
            user.setDeviceId(request.getDeviceId());
        }

        user = userRepository.save(user);
        return UserResponse.from(user);
    }
}
