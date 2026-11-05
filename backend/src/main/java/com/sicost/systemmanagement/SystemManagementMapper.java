package com.sicost.systemmanagement;

import java.util.*;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface SystemManagementMapper {
    List<Map<String,Object>> users(Map<String,Object> query);
    long userCount(Map<String,Object> query);
    Map<String,Object> user(Long id);
    Long createUser(Map<String,Object> write);
    void updateUser(Map<String,Object> write);
    List<Long> userRoles(Long id);
    List<Long> userParts(Long id);
    List<Long> userProjects(Long id);
    void clearRoles(Long id);
    void clearParts(Long id);
    void clearProjects(Long id);
    void assignRoles(Map<String,Object> write);
    void assignParts(Map<String,Object> write);
    void assignProjects(Map<String,Object> write);
    void unlock(Long id);
    List<Map<String,Object>> roles();
    Map<String,Object> role(Long id);
    Long createRole(Map<String,Object> write);
    void updateRole(Map<String,Object> write);
    void copyPermissions(Map<String,Object> write);
    List<Map<String,Object>> permissions(Long id);
    void savePermission(Map<String,Object> write);
    List<Map<String,Object>> menus();
    Long createMenu(Map<String,Object> write);
    void updateMenu(Map<String,Object> write);
    List<Map<String,Object>> history(Map<String,Object> query);
    long historyCount(Map<String,Object> query);
    void audit(Map<String,Object> write);
    boolean partExists(Long id);
    boolean projectExists(Long id);
    boolean developerExists(Long id);
    List<Map<String,Object>> channels(String month);
    void saveChannel(Map<String,Object> write);
}
