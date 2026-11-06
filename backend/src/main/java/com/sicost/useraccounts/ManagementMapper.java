package com.sicost.useraccounts;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ManagementMapper {
    List<Map<String,Object>> users(Map<String,Object> query);
    long userCount(Map<String,Object> query);
    Map<String,Object> user(long id);
    List<Long> userRoles(long id);
    List<Long> userParts(long id);
    List<Long> userProjects(long id);
    long createUser(Map<String,Object> input);
    int patchUser(Map<String,Object> input);
    int deleteAssignments(Map<String,Object> input);
    int insertAssignment(Map<String,Object> input);
    List<Map<String,Object>> roles(Map<String,Object> query);
    Map<String,Object> role(long id);
    long createRole(Map<String,Object> input);
    int patchRole(Map<String,Object> input);
    int copyPermissions(Map<String,Object> input);
    List<Map<String,Object>> permissions(long id);
    int savePermission(Map<String,Object> input);
    List<Map<String,Object>> menus();
    long createMenu(Map<String,Object> input);
    int patchMenu(Map<String,Object> input);
    List<Map<String,Object>> history(Map<String,Object> query);
    long historyCount(Map<String,Object> query);
    int revokeAll();
    boolean channel(String month);
    int saveChannel(Map<String,Object> input);
}
