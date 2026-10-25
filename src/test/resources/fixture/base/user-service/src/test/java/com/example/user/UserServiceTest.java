package com.example.user;

import org.junit.jupiter.api.Test;

public class UserServiceTest {

    @Test
    public void testIsActive() {
        UserService service = new UserService(null);
        service.isActive(1L);
    }
}
