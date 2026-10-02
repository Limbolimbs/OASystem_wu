package com.example.demo.security;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;

import jakarta.servlet.http.HttpSession;

@Service
public class AccessControlService {

    private final UserRepository userRepository;

    public AccessControlService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(readOnly = true)
    public Optional<User> currentUser(HttpSession session) {
        Object userId = session.getAttribute("loginUserId");
        if (!(userId instanceof Long id)) {
            return Optional.empty();
        }

        Optional<User> user = userRepository.findById(id)
                .filter(value -> Integer.valueOf(1).equals(value.getStatus()));
        if (user.isEmpty()) {
            session.invalidate();
        }
        return user;
    }

    public boolean isAdmin(User user) {
        return SystemRole.fromCode(user.getRole()) == SystemRole.ADMIN;
    }

    public boolean canViewAllAttendance(User user) {
        SystemRole role = SystemRole.fromCode(user.getRole());
        return role == SystemRole.ADMIN || role == SystemRole.GENERAL_AFFAIRS;
    }

    public boolean canApproveAttendance(User user) {
        return canViewAllAttendance(user);
    }

    public boolean canManageAttendancePolicy(User user) {
        return isAdmin(user);
    }
}
