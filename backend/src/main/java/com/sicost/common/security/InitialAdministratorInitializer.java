package com.sicost.common.security;

import java.util.Arrays;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class InitialAdministratorInitializer {
    private final SecurityStateMapper mapper;
    public InitialAdministratorInitializer(SecurityStateMapper mapper) { this.mapper=mapper; }
    /** Explicit one-time invocation by an operational launcher; never a default or smoke account. */
    @Transactional public Long initialize(String loginId,char[] password,String name,String email) {
        try {
            if(loginId==null || !loginId.matches("[A-Za-z0-9._-]{1,50}") || name==null || name.isBlank() || name.length()>50 ||
                email==null || email.length()>100 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+") || password==null)
                throw com.sicost.common.api.ApiException.badRequest("초기 관리자 입력을 확인하세요.",null);
            PasswordPolicy.validate(new String(password));
            if(mapper.lockAdministratorRoles().isEmpty() || mapper.administratorCount()!=0)
                throw com.sicost.common.api.ApiException.conflict("ADMINISTRATOR_ALREADY_INITIALIZED","초기 관리자 생성은 한 번만 가능합니다.",null);
            Long id=mapper.createAdministrator(new SecurityStateMapper.AdministratorWrite(loginId,AuthenticationProof.hash(new String(password)),name,email));
            mapper.assignAdministrator(id);
            return id;
        } finally { if(password!=null) Arrays.fill(password,'\0'); }
    }
}
