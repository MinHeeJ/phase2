package com.sicost.admin;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.admin.AdminModels.*;

@Mapper
public interface AdminMapper {
    List<CodeGroup> groups(AdminQuery query);
    long groupCount();
    CodeGroup group(String group);
    List<CodeRow> codes(AdminQuery query);
    long codeCount(String group);
    List<CodeRow> allCodes(String group);
    void createGroup(GroupWrite write);
    void insertCode(CodeWrite write);
    void updateCode(CodeWrite write);
    List<ExcelMap> maps(AdminQuery query);
    long mapCount();
    ExcelMap mapping(String id);
    boolean mappingExists(ExcelMapCreate input);
    boolean activeTarget(ExcelMapCreate input);
    Long insertMapping(MapCreateWrite write);
    void updateMapping(MapWrite write);
    List<CodeChange> history(AdminQuery query);
    long historyCount(AdminQuery query);
    void appendHistory(HistoryEntry entry);
    List<OperatingParam> params(AdminQuery query);
    long paramCount(AdminQuery query);
    List<OperatingParam> versions(AdminQuery query);
    long versionCount(String key);
    OperatingParam source(@Param("key") String key,@Param("month") String month);
    OperatingParam version(@Param("key") String key,@Param("month") String month);
    String lastClosed();
    void saveParam(ParamWrite write);
}
