package com.iers.auth.controller;

import com.iers.auth.dto.request.UpdateUserRequest;
import com.iers.auth.dto.response.UserResponse;
import com.iers.auth.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getMyProfile(
            @RequestHeader("X-User-Id") String userId) {
        return ResponseEntity.ok(userService.getUserById(UUID.fromString(userId)));
    }

    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateMyProfile(
            @RequestHeader("X-User-Id") String userId,
            @RequestBody UpdateUserRequest request) {
        return ResponseEntity.ok(userService.updateUser(UUID.fromString(userId), request));
    }
}
