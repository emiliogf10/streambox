package com.emilio.streambox.mapper;

import com.emilio.streambox.dto.CreateUserRequest;
import com.emilio.streambox.dto.UserResponse;
import com.emilio.streambox.entity.Role;
import com.emilio.streambox.entity.User;

import java.time.LocalDateTime;
import java.util.List;

public class UserMapper {

    private UserMapper() {
        // Evita instanciar la clase
    }

    public static User toEntity(CreateUserRequest request) {

        User user = new User();

        user.setUsername(request.getUsername());
        user.setEmail(request.getEmail());
        user.setPassword(request.getPassword());

        return user;
    }

    public static UserResponse toResponse(User user) {

        UserResponse response = new UserResponse();

        response.setId(user.getId());
        response.setUsername(user.getUsername());
        response.setEmail(user.getEmail());
        response.setRole(user.getRole());
        response.setCreatedAt(user.getCreatedAt());

        return response;
    }

    public static List<UserResponse> toResponseList(List<User> users) {

    return users.stream()
            .map(UserMapper::toResponse)
            .toList();
}
}
