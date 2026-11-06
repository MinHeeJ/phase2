package com.sicost.common.security;

import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SecurityPermissionMapper {
    record Query(long userId, String screenId, String actionColumn, LocalDate today) {}
    record GrantRow(long roleId, String scope, boolean sensitive, boolean partStandardChange, Long developerId) {}
    List<GrantRow> grants(Query query);
    List<Long> parts(long userId);
    List<Long> projects(long userId);
    int lockAdministrator();
    long recoverableAdministrators();
}
