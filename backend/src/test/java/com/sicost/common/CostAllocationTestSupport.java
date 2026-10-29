package com.sicost.common;

import com.sicost.common.costallocation.*;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

public final class CostAllocationTestSupport {
    private CostAllocationTestSupport() {}
    public static void register(AnnotationConfigApplicationContext context, SqlSessionTemplate session) {
        context.register(CostAllocationEngine.class, CostAllocationService.class);
        context.registerBean(CostAllocationMapper.class, () -> session.getMapper(CostAllocationMapper.class));
    }
}
