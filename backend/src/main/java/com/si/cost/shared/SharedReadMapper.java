package com.si.cost.shared;

import com.si.cost.common.user.DataScope;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.si.cost.shared.SharedReadModels.*;

@Mapper
public interface SharedReadMapper {
    List<MenuRow> menus(@Param("scope") DataScope scope);
    boolean groupExists(@Param("group") String group);
    List<CodeOption> codeOptions(@Param("group") String group, @Param("scope") DataScope scope);
    List<MonthClose> monthClose(@Param("from") String from, @Param("to") String to, @Param("scope") DataScope scope);
    EffectiveParam effectiveParam(@Param("key") String key, @Param("month") String month, @Param("scope") DataScope scope);
}
