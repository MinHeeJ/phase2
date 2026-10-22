package com.sicost.shared;

import com.sicost.shared.SharedReadModels.*;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SharedReadMapper {
    boolean groupExists(@Param("group") String group);
    List<CodeOption> codeOptions(@Param("group") String group);
    List<MenuRow> menus();
    List<MonthClose> monthClose(@Param("from") String from, @Param("to") String to);
    OperatingParam effectiveParam(@Param("key") String key, @Param("month") String month);
}
