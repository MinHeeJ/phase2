package com.sicost.common.security;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import static com.sicost.common.security.AccessPolicyModels.*;

@Mapper
public interface AccessPolicyMapper {
    Account activeSession(SessionLookup lookup);
    Account sessionOwner(SessionLookup lookup);
    List<Role> roles(Long userId);
    List<Long> parts(Long userId);
    List<Long> projects(Long userId);
    List<Grant> grants(PermissionLookup lookup);
    void lockAdministration();
    long recoveryAdministratorCount();
    Long insertInitialAdministrator(InitialAdministrator write);
    void assignInitialAdministrator(Long userId);
}
