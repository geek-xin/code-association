package com.example.user;

public interface UserMapper {
    User selectById(Long id);
    int updateStatus(Long id, Integer status);
}
