package com.example.demo.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.example.demo.entity.User;

public interface UserRepository extends JpaRepository<User, Long> {
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = ?1")
    Optional<User> findForUpdate(Long id);

    /**
     * 有効ユーザーを先に表示する
     */
    List<User> findAllByOrderByStatusDescIdAsc();

    @Query("select u from User u where u.status = ?1 and u.deleteFlag = 0 order by u.id")
    List<User> findByStatusOrderByIdAsc(Integer status);

    @Query("select u from User u where u.status = ?1 and u.deleteFlag = 0 order by u.id")
    Page<User> findByStatusOrderByIdAsc(Integer status, Pageable pageable);

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
    @Query("select count(u) from User u where u.status = ?1 and u.deleteFlag = 0")
    long countByStatus(Integer status);

    @Query("select count(u) from User u where u.role = ?1 and u.status = ?2 and u.deleteFlag = 0")
    long countByRoleAndStatus(String role, Integer status);

    @Query("select count(u) from User u where u.department = ?1 and u.status = ?2 and u.deleteFlag = 0")
    long countByDepartmentAndStatus(String department, Integer status);
}
