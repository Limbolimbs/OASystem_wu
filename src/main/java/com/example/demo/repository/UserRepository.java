package com.example.demo.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;

import com.example.demo.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {

    /**
     * 有効ユーザーを先に表示する
     */
    List<User> findAllByOrderByStatusDescIdAsc();

    List<User> findByStatusOrderByIdAsc(Integer status);

    Page<User> findByStatusOrderByIdAsc(Integer status, Pageable pageable);

    /**
     * ログインユーザーを検索する
     */
    Optional<User> findByUsernameAndPasswordAndStatus(
            String username,
            String password,
            Integer status
    );

    /**
     * ユーザー名からユーザーを検索する
     */
    Optional<User> findByUsername(String username);

    /**
     * ユーザー名の重複を確認する
     */
    boolean existsByUsername(String username);

    /**
     * 状態ごとのユーザー数を取得する
     */
    long countByStatus(Integer status);

    long countByRoleAndStatus(String role, Integer status);
}
