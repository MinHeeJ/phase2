package com.sicost.shared;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SharedReadMapper {
    List<MenuRow> menus();
    boolean groupExists(@Param("group") String group);
    List<CodeOption> codes(@Param("group") String group);
    List<MonthClose> months(@Param("from") String from, @Param("to") String to);
    OperatingParam parameter(@Param("key") String key, @Param("month") String month);
}
