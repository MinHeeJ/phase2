package com.sicost.persistence;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface CostMapper {
    List<Map<String, Object>> menus();
    List<Map<String, Object>> months(@Param("from") String from, @Param("to") String to);
    String lastClosedMonth();
    List<Map<String, Object>> groups();
    Map<String, Object> group(String group);
    List<Map<String, Object>> codes(String group);
    List<Map<String, Object>> options(String group);
    int insertGroup(Map<String, Object> row);
    int insertCode(Map<String, Object> row);
    int updateCode(Map<String, Object> row);
    List<Map<String, Object>> maps();
    Map<String, Object> excelMap(String mapId);
    int insertMap(Map<String, Object> row);
    int updateMap(Map<String, Object> row);
    List<Map<String, Object>> history(String group);
    int appendHistory(Map<String, Object> row);
    List<Map<String, Object>> currentParams(String month);
    List<Map<String, Object>> versions(String key);
    Map<String, Object> effective(@Param("key") String key, @Param("month") String month);
    int saveParam(Map<String, Object> row);
}
