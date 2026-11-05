package com.sicost.common.security;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SmokeAccountMapper {
    Long upsert(Map<String,Object> input);
    List<Long> roles(List<String> names);
    void clearRoles(Long id);
    void assign(Map<String,Object> input);
}
