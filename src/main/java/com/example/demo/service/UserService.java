package com.example.demo.service;

import java.util.List;

import org.springframework.stereotype.Service;

import com.example.demo.entity.User;
import com.example.demo.repository.UserRepository;

@Service
public class UserService {
	
    private final UserRepository userRepository;
    
    public UserService(UserRepository userRepository) {
    	this.userRepository = userRepository;
    }
    
    public List<User>findAll(){
    	return userRepository.findAll();
    }
    
    public User save(User user) {
    	return userRepository.save(user);
    }
}
