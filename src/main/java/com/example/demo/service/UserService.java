package com.example.demo.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.SystemRole;
import com.example.demo.security.AccountRules;
import com.example.demo.security.PasswordHashes;
import com.example.demo.security.PortalSessionFilter;
import org.springframework.security.crypto.password.PasswordEncoder;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String missingUserHash;
    private final com.example.demo.security.AccountSecurityState securityState;
    private final DepartmentService departments;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       com.example.demo.security.AccountSecurityState securityState,
                       DepartmentService departments) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.securityState = securityState;
        this.departments = departments;
        this.missingUserHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
    }

    /**
     * ユーザー一覧を取得する
     */
    public List<User> findAll() {
        return userRepository.findAllByOrderByStatusDescIdAsc();
    }

    /**
     * IDからユーザーを取得する
     */
    public Optional<User> findById(Long id) {
        return userRepository.findById(id);
    }

    /**
     * ユーザー名からユーザーを取得する
     */
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    /**
     * ユーザーを保存する
     */
    @com.example.demo.security.BusinessAudit(operation = "USER_REGISTER", target = "USER")
    public User save(User user) {
        if (user.getId() == null) {
            AccountRules.validateLoginId(user.getUsername());
            AccountRules.validateNewPassword(user.getPassword());
            // 管理排他を取得した保存TX内でも所属を照合する。
            if (user.getDepartment() != null && !user.getDepartment().isBlank()) {
                departments.requireActive(user.getDepartment());
            }
            user.setUsername(user.getUsername().trim());
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            throw new IllegalArgumentException("登録済みユーザーは専用の変更処理を使用してください。");
        }
        if (user.getRole() == null || !user.getRole().equals(SystemRole.fromCode(user.getRole()).name())) {
            throw new IllegalArgumentException("権限の入力値が不正です。");
        }
        if (user.getStatus() == null) {
            user.setStatus(1);
        }

        return userRepository.save(user);
    }

    /** 本人のパスワード変更。ハッシュや任意のユーザーDTOによる更新を受け付けない。 */
    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "PASSWORD_CHANGE", target = "USER", idArgument = 0)
    public void changePassword(Long userId, String currentPassword, String newPassword) {
        securityState.lockManagement();
        User user = userRepository.findForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("対象ユーザーが見つかりません。"));
        AccountRules.validateNewPassword(newPassword);
        if (!user.isActive() || !PasswordHashes.isSupported(user.getPassword()) || currentPassword == null
                || currentPassword.codePointCount(0, currentPassword.length()) > 255
                || !passwordEncoder.matches(currentPassword, user.getPassword())) {
            throw new IllegalArgumentException("現在のパスワードを確認してください。");
        }
        if (passwordEncoder.matches(newPassword, user.getPassword())) {
            throw new IllegalArgumentException("現在とは異なるパスワードを設定してください。");
        }
        user.setPassword(passwordEncoder.encode(newPassword));
        securityState.revoke(user);
    }

    /**
     * ログイン可能な有効ユーザーを取得する
     */
    public User findLoginUser(String username, String password) {
        User user = username == null ? null : userRepository.findByUsername(username).orElse(null);
        boolean eligible = user != null && user.isActive() && PortalSessionFilter.knownRole(user.getRole())
                && PasswordHashes.isSupported(user.getPassword()) && password != null && !password.isBlank()
                && password.codePointCount(0, password.length()) <= 255;
        String stored = eligible ? user.getPassword() : missingUserHash;
        boolean matches = passwordEncoder.matches(password == null ? "" : password, stored);
        return eligible && matches ? user : null;
    }

    /**
     * 既存コードとの互換性を維持する
     */
    public int findOne(String username, String password) {
        User user = findLoginUser(username, password);
        return user == null ? 0 : 1;
    }

    /**
     * ユーザーを論理削除する
     */
    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "USER_DEACTIVATE", target = "USER", idArgument = 0)
    public boolean deactivate(Long id) {
        securityState.lockManagement();
        Optional<User> optionalUser = userRepository.findForUpdate(id);

        if (optionalUser.isEmpty()) {
            return false;
        }

        User user = optionalUser.get();
        if (user.isActive() && "ADMIN".equals(user.getRole())
                && securityState.activeAdministratorCount() <= 1) {
            throw new IllegalStateException("最後の有効な管理者は無効化できません。");
        }
        if (user.isActive()) securityState.revoke(user);
        user.softDelete();
        userRepository.save(user);

        return true;
    }

    /**
     * 無効ユーザーを再有効化する
     */
    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "USER_ACTIVATE", target = "USER", idArgument = 0)
    public boolean activate(Long id) {
        securityState.lockManagement();
        Optional<User> optionalUser = userRepository.findForUpdate(id);

        if (optionalUser.isEmpty()) {
            return false;
        }

        User user = optionalUser.get();
        if (user.getDepartment() != null && !user.getDepartment().isBlank()) {
            try {
                departments.requireActive(user.getDepartment());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("所属部署が無効または未登録です。有効な部署に変更するか、元の部署を再有効化してから、従業員を有効化してください。");
            }
        }
        if (!user.isActive()) securityState.revoke(user);
        user.restore();
        userRepository.save(user);

        return true;
    }

    /**
     * 有効ユーザー数を取得する
     */
    public long countActiveUsers() {
        return userRepository.countByStatus(1);
    }

    /**
     * ユーザー名の重複を確認する
     */
    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    @Transactional
    @com.example.demo.security.BusinessAudit(operation = "USER_ROLE_CHANGE", target = "USER", idArgument = 0)
    public void updateRole(Long userId, SystemRole role) {
        if (role == null) throw new IllegalArgumentException("権限を選択してください。");
        securityState.lockManagement();
        User user = userRepository.findForUpdate(userId)
                .orElseThrow(() -> new IllegalArgumentException("対象ユーザーが見つかりません。"));
        if (user.isActive() && "ADMIN".equals(user.getRole()) && role != SystemRole.ADMIN
                && securityState.activeAdministratorCount() <= 1) {
            throw new IllegalStateException("最後の有効な管理者の権限は変更できません。");
        }
        if (!role.name().equals(user.getRole())) securityState.revoke(user);
        user.setRole(role.name());
    }
}
