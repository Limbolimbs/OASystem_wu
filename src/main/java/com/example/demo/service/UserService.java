package com.example.demo.service;

import com.example.demo.attendance.AttendanceClock;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;
import com.example.demo.security.SystemRole;

@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
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
    public User save(User user) {
        if (user.getRole() == null || !user.getRole().equals(SystemRole.fromCode(user.getRole()).name())) {
            throw new IllegalArgumentException("権限の入力値が不正です。");
        }
        if (user.getStatus() == null) {
            user.setStatus(1);
        }

        return userRepository.save(user);
    }

    /**
     * ログイン可能な有効ユーザーを取得する
     */
    public User findLoginUser(String username, String password) {
        return userRepository
                .findByUsernameAndPasswordAndStatus(
                        username,
                        password,
                        1
                )
                .orElse(null);
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
    public boolean deactivate(Long id) {
        Optional<User> optionalUser = userRepository.findById(id);

        if (optionalUser.isEmpty()) {
            return false;
        }

        User user = optionalUser.get();
        user.setStatus(0);
        user.setDeletedAt(AttendanceClock.now());
        userRepository.save(user);

        return true;
    }

    /**
     * 無効ユーザーを再有効化する
     */
    @Transactional
    public boolean activate(Long id) {
        Optional<User> optionalUser = userRepository.findById(id);

        if (optionalUser.isEmpty()) {
            return false;
        }

        User user = optionalUser.get();
        user.setStatus(1);
        user.setDeletedAt(null);
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
    public void updateRole(Long userId, SystemRole role) {
        if (role == null) throw new IllegalArgumentException("権限を選択してください。");
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("対象ユーザーが見つかりません。"));
        if ("ADMIN".equals(user.getRole()) && role != SystemRole.ADMIN
                && userRepository.countByRoleAndStatus("ADMIN", 1) <= 1) {
            throw new IllegalStateException("最後の有効な管理者の権限は変更できません。");
        }
        user.setRole(role.name());
    }
}
