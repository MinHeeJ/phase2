package com.sicost.changes;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.changes.AssignmentChangeModels.*;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AssignmentChangeMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        String path = "mappers/changes/AssignmentChangeMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
        }
        return c;
    }
    String sql(Configuration c, String method, Object parameter) {
        return c.getMappedStatement(AssignmentChangeMapper.class.getName() + "." + method)
            .getBoundSql(parameter).getSql();
    }
    @Test void everyMapperMethodIsRegisteredWithNamedConstructorProjections() throws Exception {
        var c = configuration();
        for (var method : AssignmentChangeMapper.class.getDeclaredMethods()) {
            assertThat(c.hasStatement(AssignmentChangeMapper.class.getName() + "." + method.getName())).isTrue();
        }
        for (var entry : Map.of("state", State.class, "header", Header.class, "changeRow", ChangeRow.class,
                "rateRow", RateRow.class, "monthRow", MonthRow.class, "warning", Warning.class).entrySet()) {
            var projection = c.getResultMap(AssignmentChangeMapper.class.getName() + "." + entry.getKey());
            assertThat(projection.getType()).isEqualTo(entry.getValue());
            assertThat(projection.getConstructorResultMappings()).hasSize(entry.getValue().getRecordComponents().length);
        }
    }
    @Test void historyFilterIsDynamicAndIncludesFixedTargetsAndStableOrdering() throws Exception {
        var c = configuration();
        String all = sql(c, "list", new ChangeFilter(1, null));
        String selected = sql(c, "list", new ChangeFilter(1, "EXTEND"));
        assertThat(all).doesNotContain("h.asgn_chg_ty_cd=?", "fixed_yn='N'", "IS NULL OR");
        assertThat(selected).contains("h.asgn_chg_ty_cd=?", "h.aply_dt DESC,h.chg_at DESC", "fix_trgt_aply_dt", "u.user_nm AS chg_user_nm");
    }
    @Test void fixStatementChangesOnlyTargetMarkerAndNeverDeletesHistory() throws Exception {
        String sql = sql(configuration(), "markFixed", new ChangeKey(1, 2));
        assertThat(sql).contains("SET fixed_yn='Y'", "asgn_id=?", "asgn_chg_id=?", "NOT IN ('START','FIX')")
            .doesNotContain("bfr_val=", "aft_val=", "DELETE");
    }
}
