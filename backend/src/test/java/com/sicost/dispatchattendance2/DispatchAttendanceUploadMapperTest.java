package com.sicost.dispatchattendance2;

import static org.assertj.core.api.Assertions.*;
import java.time.YearMonth;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

class DispatchAttendanceUploadMapperTest {
    @Test void everyMapperMethodHasAStatementWithRealScopeProperties() throws Exception {
        var configuration=new Configuration();
        String file="mappers/dispatchattendance2/DispatchAttendanceUploadMapper.xml";
        try(var input=new ClassPathResource(file).getInputStream()) {
            new XMLMapperBuilder(input,configuration,file,configuration.getSqlFragments()).parse();
        }
        String prefix=DispatchAttendanceUploadMapper.class.getName()+".";
        for(var method:DispatchAttendanceUploadMapper.class.getDeclaredMethods()) {
            assertThat(configuration.hasStatement(prefix+method.getName())).as(method.getName()).isTrue();
        }
        var month=YearMonth.of(2026,10);
        var scope=new Scope(7,"2026-10",month.atDay(1),month.atEndOfMonth());
        var query=configuration.getMappedStatement(prefix+"sources").getBoundSql(scope);
        assertThat(query.getParameterMappings()).extracting(p->p.getProperty()).contains("first","last","projectId","month");
        assertThat(configuration.getMappedStatement(prefix+"deleteAttendance").getBoundSql(scope).getSql())
            .contains("prj_id=", "work_ym=").doesNotContain("bill_dtl","asgn_mth","untprc_hist");
        assertThat(configuration.getMappedStatement(prefix+"confirmedUsage").getBoundSql(scope).getSql())
            .contains("bill_stts_cd='CONFIRM'", "d.work_ym=");
    }
}
