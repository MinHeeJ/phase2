package com.sicost.common;

import com.sicost.billingsops.BillingMapper;
import com.sicost.billingsops.BillingModels;
import com.sicost.costallocation.CostAllocationMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;

class CostAllocationMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        for (String path : List.of("mappers/costallocation/CostAllocationMapper.xml", "mappers/billingsops/BillingMapper.xml")) {
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
            }
        }
        return c;
    }

    @Test void everyProjectionMatchesRecordConstructorLabelsTypesAndOrderWithoutDatabase() throws Exception {
        var c = configuration();
        String ns = CostAllocationMapper.class.getName() + ".";
        for (var method : CostAllocationMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ns + method.getName())).isTrue();
        var projections = Map.of("person", Person.class, "assignment", Assignment.class, "expense", Expense.class,
            "turnkey", Turnkey.class, "allocation", AllocationRow.class);
        for (var entry : projections.entrySet()) check(c, ns + entry.getKey(), entry.getValue());
        check(c, BillingMapper.class.getName() + ".projectRevenue", BillingModels.ProjectRevenue.class);
    }

    private void check(Configuration c, String map, Class<?> type) {
        var components = type.getRecordComponents();
        var mappings = c.getResultMap(map).getConstructorResultMappings();
        assertThat(mappings).hasSize(components.length);
        for (int i = 0; i < components.length; i++) {
            assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
            assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
        }
    }

    @Test void sourceQueriesUseActualIdentitySourceMmAndNeverBillingAttendance() throws Exception {
        var c = configuration();
        String ns = CostAllocationMapper.class.getName() + ".";
        for (String method : List.of("people", "assignments", "expenses", "turnkey", "snapshots")) {
            String sql = c.getMappedStatement(ns + method).getBoundSql("2026-10").getSql();
            assertThat(sql).doesNotContain("dspch_atnd", "bill_dtl", "cntrct_dev_id", "INSERT", "UPDATE", "DELETE");
        }
        String sql = c.getMappedStatement(ns + "assignments").getBoundSql("2026-10").getSql();
        assertThat(sql).contains("a.dev_id", "m.mm_qty", "asgn.asgn_mth", "a.asgn_stts_cd!='CANCELLED'");
    }

    @Test void turnkeyStageIsSelectedAtProjectScopeBeforeMonthAndPersonFiltering() throws Exception {
        String sql = configuration().getMappedStatement(CostAllocationMapper.class.getName() + ".turnkey")
            .getBoundSql("2026-10").getSql();
        assertThat(sql).contains("WHEN 'ACTUAL' THEN 3 WHEN 'FINAL' THEN 2", "GROUP BY l.prj_id", "l.line_ty_cd='PERSON'");
        assertThat(sql.indexOf("GROUP BY l.prj_id")).isLessThan(sql.indexOf("m.aloc_ym=?"));
    }

    @Test void confirmedSupplyQueryIsProjectScopedAndOptionalPredicateOnlyBindsPresentId() throws Exception {
        var statement = configuration().getMappedStatement(BillingMapper.class.getName() + ".projectRevenue");
        var all = statement.getBoundSql(new BillingModels.CostRevenueQuery(2026, null));
        var one = statement.getBoundSql(new BillingModels.CostRevenueQuery(2026, 101L));
        assertThat(all.getSql()).doesNotContain("b.prj_id=?", "IS NULL OR", "rcpt");
        assertThat(one.getSql()).contains("b.prj_id=?", "b.bill_stts_cd='CONFIRM'", "sum(b.sply_amt)", "ELSE b.atrb_ym END", "GROUP BY b.prj_id");
        assertThat(all.getParameterMappings()).hasSize(1);
        assertThat(one.getParameterMappings()).hasSize(2);
        assertThat(one.getSql()).doesNotContain("vat_amt", "tot_amt", "bill_dtl", "cst_aloc");
    }
}
