package com.sicost.authentication;

import com.sicost.common.security.InitialAdministratorInitializer;
import com.sicost.common.security.SecurityStateMapper;
import org.junit.jupiter.api.Test;
import static org.mockito.Mockito.*;

class AdministratorBootstrapTest {
    @Test void missingAdministratorSlotsDoNotDependOnSmokeAccount() {
        var initializer=mock(InitialAdministratorInitializer.class);
        var state=mock(SecurityStateMapper.class);
        var bootstrap=new AdministratorBootstrap(initializer,state);
        bootstrap.initialize(null,null,null);
        bootstrap.initialize("","","");
        verifyNoInteractions(initializer,state);
    }
}
