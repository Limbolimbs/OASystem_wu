package com.example.demo.security;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.service.DepartmentService;

import jakarta.servlet.http.HttpSession;

@Service
public class AccessControlService {

    private final UserRepository userRepository;
    private final DepartmentService departmentService;

    public AccessControlService(UserRepository userRepository, DepartmentService departmentService) {
        this.userRepository = userRepository;
        this.departmentService = departmentService;
    }

    @Transactional(readOnly = true)
    public Optional<User> currentUser(HttpSession session) {
        Object userId = session.getAttribute("loginUserId");
        if (!(userId instanceof Long id)) {
            return Optional.empty();
        }

        Optional<User> user = userRepository.findById(id)
                .filter(value -> value.isActive());
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

    public boolean canManageSales(User user) {
        return isAdmin(user) || departmentService.isSalesDepartment(user.getDepartment());
    }
}
