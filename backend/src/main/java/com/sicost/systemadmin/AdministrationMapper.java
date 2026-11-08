package com.sicost.systemadmin;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface AdministrationMapper {
    List<Map<String,Object>> users(Map<String,Object> query);
    long userCount(Map<String,Object> query);
    Map<String,Object> user(Long id);
    Long insertUser(Map<String,Object> write);
    int updateUser(Map<String,Object> write);
    List<Long> userRoles(Long id);
    List<Long> userParts(Long id);
    List<Long> userProjects(Long id);
    void clearRoles(Long id);
    void clearParts(Long id);
    void clearProjects(Long id);
    void addRole(@Param("id") Long id,@Param("value") Long value);
    void addPart(@Param("id") Long id,@Param("value") Long value);
    void addProject(@Param("id") Long id,@Param("value") Long value);
    boolean validReference(@Param("kind") String kind,@Param("id") Long id);
    List<Map<String,Object>> roles();
    Map<String,Object> role(Long id);
    Long insertRole(Map<String,Object> write);
    void updateRole(Map<String,Object> write);
    List<Map<String,Object>> permissions(Long id);
    void savePermission(Map<String,Object> write);
    void copyPermissions(@Param("source") Long source,@Param("target") Long target);
    List<Map<String,Object>> menus();
    Long insertMenu(Map<String,Object> write);
    void updateMenu(Map<String,Object> write);
    List<Long> lockAdministrators();
    long readyAdministrators();
    void revokeUser(Long id);
    void revokeAll();
    void unlock(Long id);
    void audit(Map<String,Object> entry);
    List<Map<String,Object>> history(Map<String,Object> query);
    long historyCount(Map<String,Object> query);
    boolean mailEnabled();
    void saveMailChannel(Map<String,Object> write);
}
