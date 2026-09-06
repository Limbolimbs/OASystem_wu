package com.example.demo.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.example.demo.entity.User;


public interface UserRepository extends JpaRepository<User,Long>{
	@Query("SELECT count(u) FROM User u WHERE u.username = :userName AND u.password = :password")
	public int countUser(@Param(value = "userName") String userName,@Param(value = "password")String password);
}

