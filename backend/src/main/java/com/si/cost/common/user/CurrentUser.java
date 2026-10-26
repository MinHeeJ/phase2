package com.si.cost.common.user;

import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class CurrentUser {
    public Long userId() { return null; }
    public String name() { return "기본 사용자"; }
    public List<Long> roleIds() { return List.of(1L); }
    public DataScope dataScope() { return DataScope.ALL; }
}
