package com.sicost.common.security;

import com.sicost.common.api.ApiException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import static com.sicost.common.security.AccessPolicyModels.*;

@Service
public class InitialAdministratorService {
    private final AccessPolicyMapper mapper;
    private final PasswordEncoder encoder;
    public InitialAdministratorService(AccessPolicyMapper mapper,PasswordEncoder encoder) { this.mapper=mapper;this.encoder=encoder; }
    @Transactional
    public boolean initialize(String loginId,String password,String email) {
        if(loginId==null || password==null || email==null) return false;
        String login=loginId.trim();
        if(login.isEmpty() || login.length()>50 || email.length()>100 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw ApiException.badRequest("초기 관리자 설정을 확인하세요.",null);
        }
        PasswordRules.validate(password,password);
        mapper.lockAdministration();
        Long id=mapper.insertInitialAdministrator(new InitialAdministrator(login,encoder.encode(password),email));
        if(id==null) return false;
        mapper.assignInitialAdministrator(id);
        return true;
    }
}
