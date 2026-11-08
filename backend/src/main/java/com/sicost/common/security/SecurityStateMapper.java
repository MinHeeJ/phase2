package com.sicost.common.security;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import static com.sicost.common.security.SecurityModels.*;

@Mapper
public interface SecurityStateMapper {
    Account account(Long userId);
    List<Long> roles(Long userId);
    List<Long> parts(Long userId);
    List<Long> projects(Long userId);
    List<Permission> permissions(Long userId);
    Session session(String hash);
    void insertSession(SessionWrite write);
    int activity(@Param("hash") String hash, @Param("now") LocalDateTime now);
    int revoke(@Param("hash") String hash, @Param("now") LocalDateTime now);
    int revokeUser(@Param("userId") Long userId, @Param("now") LocalDateTime now);
    int cleanup(LocalDateTime now);
    List<Long> lockAdministratorRoles();
    long administratorCount();
    Long createAdministrator(AdministratorWrite write);
    void assignAdministrator(Long userId);
    record AdministratorWrite(String loginId,String passwordHash,String name,String email) {
        @Override public String toString() { return "AdministratorWrite[redacted]"; }
    }
}
