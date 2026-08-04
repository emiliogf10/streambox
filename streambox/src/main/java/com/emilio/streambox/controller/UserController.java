package com.emilio.streambox.controller;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;
import com.emilio.streambox.mapper.UserMapper;
import com.emilio.streambox.service.UserService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public List<User> getUsers() {
        return userService.getAllUsers();
    }

    @PostMapping
    public UserResponse createUser(@Valid @RequestBody CreateUserRequest request) {

        User user = UserMapper.toEntity(request);

        User savedUser = userService.saveUser(user);

        return UserMapper.toResponse(savedUser);
    }
}