package com.example.user;

public class UserSummary {
    private final Long id;

    public UserSummary(User user) {
        this.id = user == null ? null : user.getId();
    }

    public Long getId() {
        return id;
    }
}
