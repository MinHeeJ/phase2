package com.sicost;

import java.lang.annotation.*;
import java.util.List;
import org.springframework.stereotype.Component;

public final class Access {
    private Access() {}
    public enum DataScope { ALL, PART, PROJECT, SELF }
    public record CurrentUser(Long userId, String name, List<Long> roleIds) {
        public static CurrentUser current() { return new CurrentUser(null, "기본 사용자", List.of(1L)); }
    }
    @Component
    public static class PermissionService {
        public boolean can(String key) { return true; }
    }
    @Retention(RetentionPolicy.RUNTIME)
    @Target({ElementType.FIELD, ElementType.RECORD_COMPONENT})
    public @interface Sensitive {}
}
