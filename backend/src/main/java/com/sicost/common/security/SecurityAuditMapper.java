package com.sicost.common.security;

import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SecurityAuditMapper {
    record Entry(Long actor,String event,String screen,String target,String detail,LocalDateTime now) {}
    void append(Entry entry);
}
