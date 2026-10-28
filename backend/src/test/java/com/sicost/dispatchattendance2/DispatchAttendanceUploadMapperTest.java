package com.sicost.dispatchattendance2;

import static org.assertj.core.api.Assertions.*;
import com.sicost.common.api.DeveloperPaging;
import java.math.BigDecimal;
import java.util.List;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;

class DispatchAttendanceUploadMapperTest {
    @Test void everyMapperMethodHasAnXmlStatementAndTypedRecordPropertiesBind() throws Exception {
        var configuration = new Configuration();
        String file = "mappers/dispatchattendance2/DispatchAttendanceUploadMapper.xml";
        try (var in = new ClassPathResource(file).getInputStream()) {
            new XMLMapperBuilder(in, configuration, file, configuration.getSqlFragments()).parse();
        }
        String prefix = DispatchAttendanceUploadMapper.class.getName() + ".";
        for (var method : DispatchAttendanceUploadMapper.class.getDeclaredMethods()) {
            assertThat(configuration.hasStatement(prefix + method.getName())).as(method.getName()).isTrue();
        }
        var scope = new Scope(11, "2026-10");
        var input = new Attendance(null, 99L, 22, BigDecimal.TEN, null, BigDecimal.ZERO, BigDecimal.TEN);
        var bound = configuration.getMappedStatement(prefix + "insertAttendance")
            .getBoundSql(new AttendanceWrite(scope, input, 123));
        var meta = configuration.newMetaObject(bound.getParameterObject());
        for (var binding : bound.getParameterMappings()) assertThat(meta.hasGetter(binding.getProperty())).isTrue();
        assertThat(bound.getSql()).contains("RETURNING atnd_id").doesNotContain("asgn.asgn_mth", "bill.bill_dtl", "untprc_hist");
        assertThat(configuration.getMappedStatement(prefix + "deleteAttendance").getBoundSql(scope).getSql())
            .contains("prj_id=", "work_ym=");
    }
}
