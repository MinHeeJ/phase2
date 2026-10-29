package com.sicost.dispatchattendance;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;
import static org.assertj.core.api.Assertions.*;

class DispatchAttendanceMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/dispatchattendance/DispatchAttendanceMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void everyMethodAndConstructorLabelTypeOrderMatchRealRecords() throws Exception {
        var c = configuration();
        for (var method : DispatchAttendanceMapper.class.getDeclaredMethods())
            assertThat(c.hasStatement(DispatchAttendanceMapper.class.getName() + "." + method.getName())).isTrue();
        for (var entry : Map.of("project", Project.class, "source", Source.class, "stored", Stored.class).entrySet()) {
            var map = c.getResultMap(DispatchAttendanceMapper.class.getName() + "." + entry.getKey());
            var mappings = map.getConstructorResultMappings();
            var fields = entry.getValue().getRecordComponents();
            assertThat(mappings).hasSize(fields.length);
            for (int i = 0; i < fields.length; i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(fields[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(fields[i].getType());
            }
        }
    }
    @Test void sourceQueriesHaveExactScopeEffectivePriceAndNoSourceWrites() throws Exception {
        var c = configuration();
        var scope = DispatchAttendanceInput.scope("2026-10", "1");
        String sql = c.getMappedStatement(DispatchAttendanceMapper.class.getName() + ".sources").getBoundSql(scope).getSql();
        assertThat(sql).contains("a.prj_id=?", "p.cntrct_ty_cd='DP'", "a.untprc_base_cd=p.stlm_mthd_cd",
            "a.asgn_stts_cd!='CANCELLED'", "a.bgn_dt <= ?", "a.end_dt >= ?", "GREATEST(?,a.bgn_dt)",
            "ORDER BY h.aply_bgn_dt DESC LIMIT 1", "b.bill_stts_cd='CONFIRM'", "d.work_ym=?", "PARTITION BY a.dev_id");
        for (String statement : List.of("upsert", "audit")) {
            Object parameter = statement.equals("audit") ? new AttendanceAudit("1", "INSERT", null, "{}", null)
                : new AttendanceWrite(scope, new AttendanceInput(1, 2, null, null, null, null), null);
            String write = c.getMappedStatement(DispatchAttendanceMapper.class.getName() + "." + statement).getBoundSql(parameter).getSql();
            assertThat(write).doesNotContain("UPDATE asgn.", "INSERT INTO asgn.", "bill.bill_dtl");
        }
    }
    @Test void nullableOuterJoinProjectionHasBoxedNullableIdentifiersAndDecimals() throws Exception {
        var c = configuration();
        assertThat(c.getResultMap(DispatchAttendanceMapper.class.getName() + ".stored").getConstructorResultMappings().get(1).getJavaType())
            .isEqualTo(Long.class);
        var source = new Source(17, 29, null, false, 1, false, null, null, null, null);
        assertThat(source.assignment_mm()).isNull();
        assertThat(DispatchAttendanceService.calculatedMm(source.work_day_qty(), source.mth_work_day_qty())).isNull();
    }
}
