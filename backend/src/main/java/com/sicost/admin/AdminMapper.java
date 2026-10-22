package com.sicost.admin;

import com.sicost.admin.AdminModels.*;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdminMapper {
    List<CodeGroup> groups(AdminListQuery query);
    long groupCount();
    CodeGroup group(String group);
    List<CodeRow> codes(AdminListQuery query);
    long codeCount(String group);
    List<ExcelMap> excelMaps(AdminListQuery query);
    long excelCount();
    ExcelMap excelMap(long mapId);
    long excelDuplicate(ExcelMapCreate row);
    int activeCode(@Param("group") String group, @Param("code") String code);
    List<CodeChange> history(AdminListQuery query);
    long historyCount(AdminListQuery query);
    List<OperatingParam> params(AdminListQuery query);
    long paramCount(String month);
    List<OperatingParam> versions(AdminListQuery query);
    long versionCount(String key);
    OperatingParam source(@Param("key") String key, @Param("month") String month);
    String lastClosed();
    int insertGroup(GroupWrite write);
    int insertCode(CodeWrite write);
    int updateCode(CodeWrite write);
    int insertExcel(ExcelInsert write);
    int patchExcel(ExcelPatch write);
    int saveParam(ParamWrite write);
    int insertHistory(HistoryWrite write);
}
