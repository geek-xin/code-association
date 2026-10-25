package com.example.user;

public class UserService {

    private final UserMapper userMapper;

    public UserService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    public User getUser(Long userId) {
        return userMapper.selectById(userId);
    }

    public boolean isActive(Long userId) {
        User user = getUser(userId);
        return user != null;
    }

    public void disable(Long userId) {
        userMapper.updateStatus(userId, 0);
    }
}
