package com.example.demo.service;

import java.util.List;
import java.util.Objects;
import com.example.demo.entity.Department;
import com.example.demo.entity.User;
import com.example.demo.repository.DepartmentRepository;
import com.example.demo.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class DepartmentService {
    private final DepartmentRepository departments;
    private final UserRepository users;

    public DepartmentService(DepartmentRepository departments, UserRepository users) {
        this.departments = departments;
        this.users = users;
    }

    public List<Department> findAll() { return departments.findAllByOrderByStatusDescIdAsc(); }
    public List<Department> activeDepartments() { return departments.findByStatusOrderByIdAsc(1); }
    public boolean isSalesDepartment(String name) {
        return name != null && departments.findByNameAndStatus(name, 1)
                .map(Department::isSalesEnabled).orElse(false);
    }
    public Department requireActive(String name) {
        return departments.findByNameAndStatus(name == null ? "" : name.trim(), 1)
                .orElseThrow(() -> new IllegalArgumentException("有効な部署を選択してください。"));
    }

    public Department requireRegistered(String name) {
        return departments.findByName(name == null ? "" : name.trim())
                .orElseThrow(() -> new IllegalArgumentException("登録済みの部署を選択してください。"));
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "DEPARTMENT_REGISTER", target = "DEPARTMENT")
    public Department create(String code, String name, boolean salesEnabled) {
        code = code == null ? "" : code.trim();
        name = name == null ? "" : name.trim();
        if (!code.matches("[A-Z0-9_-]{1,50}") || name.isBlank() || name.length() > 50) {
            throw new IllegalArgumentException("部署コードは半角英大文字・数字・記号（_ -）、部署名は50文字以内で入力してください。");
        }
        if (departments.existsByCodeOrName(code, name)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "部署コードまたは部署名は登録済みです。");
        }
        Department department = new Department();
        department.setCode(code);
        department.setName(name);
        department.setSalesEnabled(salesEnabled);
        departments.saveAndFlush(department);
        return department;
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "DEPARTMENT_CHANGE", target = "DEPARTMENT", idArgument = 0)
    public void update(Long id, Long version, boolean salesEnabled, Integer status) {
        Department department = departments.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "部署が見つかりません。"));
        if (version == null || !Objects.equals(version, department.getVersion())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "部署情報が更新されています。一覧を再取得してください。");
        }
        if (!Integer.valueOf(0).equals(status) && !Integer.valueOf(1).equals(status)) {
            throw new IllegalArgumentException("部署の状態を選択してください。");
        }
        if (status == 0 && users.countByDepartmentAndStatus(department.getName(), 1) > 0) {
            throw new IllegalArgumentException("有効な従業員が所属する部署は無効化できません。先に所属を変更してください。");
        }
        department.setSalesEnabled(salesEnabled);
        department.setStatus(status);
        departments.flush();
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "USER_DEPARTMENT_CHANGE", target = "USER", idArgument = 0)
    public void assignEmployee(Long userId, String name) {
        Department department = requireActive(name);
        User user = users.findById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "従業員が見つかりません。"));
        user.setDepartment(department.getName());
        users.flush();
    }
}
