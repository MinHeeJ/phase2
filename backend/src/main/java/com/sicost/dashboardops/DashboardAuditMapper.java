package com.sicost.dashboardops;

import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface DashboardAuditMapper {
    record Download(String name,String description,Long actor) {}
    int download(Download download);
}
