package com.sicost.common.security;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public record User(Long userId, String name, List<Long> roleIds) {}

    public User get() {
        return new User(null, "기본 사용자", List.of(1L));
    }

    public Long userId() { return get().userId(); }
    public DataScope dataScope() { return DataScope.ALL; }
}
