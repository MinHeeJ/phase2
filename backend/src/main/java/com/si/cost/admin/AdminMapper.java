package com.si.cost.admin;

import com.si.cost.common.api.PageRequest;
import com.si.cost.common.user.DataScope;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.si.cost.admin.AdminModels.*;

@Mapper
public interface AdminMapper {
    List<Group> groups(@Param("page") PageRequest page, @Param("scope") DataScope scope);
    long groupCount();
    Group group(String group);
    List<Code> codes(@Param("group") String group, @Param("page") PageRequest page, @Param("scope") DataScope scope);
    long codeCount(String group);
    List<Code> allCodes(String group);
    int insertGroup(GroupCreate group);
    int insertCode(@Param("group") String group, @Param("row") Code row);
    int updateCode(@Param("group") String group, @Param("code") String code, @Param("changes") Map<String, Object> changes);
    List<ExcelMap> maps(@Param("page") PageRequest page, @Param("scope") DataScope scope);
    long mapCount();
    ExcelMap excelMap(long id);
    boolean activeTarget(@Param("group") String group, @Param("code") String code);
    boolean duplicateMap(MapCreate row);
    long insertMap(MapCreate row);
    int updateMap(@Param("id") long id, @Param("changes") Map<String, Object> changes);
    List<Change> history(@Param("group") String group, @Param("page") PageRequest page, @Param("scope") DataScope scope);
    long historyCount(String group);
    int audit(Audit event);
    List<AdminModels.Param> currentParams(@Param("month") String month, @Param("page") PageRequest page, @Param("scope") DataScope scope);
    long currentParamCount(String month);
    List<AdminModels.Param> versions(@Param("key") String key, @Param("page") PageRequest page, @Param("scope") DataScope scope);
    long versionCount(String key);
    ParamSource paramSource(String key);
    AdminModels.Param paramBefore(@Param("key") String key, @Param("month") String month);
    String lastClosed();
    int saveParam(@Param("source") ParamSource source, @Param("month") String month, @Param("value") ParamSave value);
}
