package com.sicost.common;

import com.sicost.common.costallocation.*;
import com.sicost.billingsops.BillingMapper;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.mapping.SqlCommandType;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.common.costallocation.CostAllocationModels.*;
import static org.assertj.core.api.Assertions.*;

class CostAllocationMapperTest {
    Configuration configuration() throws Exception {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        for (String path : List.of("mappers/costallocation/CostAllocationMapper.xml", "mappers/billingsops/BillingMapper.xml")) {
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }
    @Test void everyProjectionMatchesExactRecordConstructorLabelsTypesAndOrderWithoutDatabase() throws Exception {
        var c = configuration(); String ns = CostAllocationMapper.class.getName() + ".";
        for (var method : CostAllocationMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ns + method.getName())).isTrue();
        var projections = Map.of("person", Person.class, "assignment", Assignment.class,
            "expense", Expense.class, "allocation", Allocation.class);
        for (var entry : projections.entrySet()) {
            var components = entry.getValue().getRecordComponents();
            var mappings = c.getResultMap(ns + entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for (int i = 0; i < components.length; i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    @Test void everyAllocationStatementIsReadOnlyAndSourcesHaveNoProjectDenominatorFilter() throws Exception {
        var c = configuration(); String ns = CostAllocationMapper.class.getName() + ".";
        for (var method : CostAllocationMapper.class.getDeclaredMethods())
            assertThat(c.getMappedStatement(ns + method.getName()).getSqlCommandType()).isEqualTo(SqlCommandType.SELECT);
        for (String method : List.of("people", "assignments", "expenses")) {
            var bound = c.getMappedStatement(ns + method).getBoundSql("2026-10");
            assertThat(bound.getParameterMappings()).extracting(p -> p.getProperty()).doesNotContain("projectId");
        }
        String sql = c.getMappedStatement(ns + "assignments").getBoundSql("2026-10").getSql();
        assertThat(sql).contains("a.dev_id", "m.mm_qty", "a.asgn_stts_cd!='CANCELLED'", "l.line_ty_cd='PERSON'")
            .doesNotContain("dspch_atnd", "bill_dtl", "cntrct_dev_id");
    }
    @Test void optionalFiltersBindOnlyPresentValuesAndClosedAmountsComeOnlyFromSnapshots() throws Exception {
        var c = configuration(); String ns = CostAllocationMapper.class.getName() + ".";
        var statement = c.getMappedStatement(ns + "snapshots");
        var all = statement.getBoundSql(new Scope("2026-10", null));
        var one = statement.getBoundSql(new Scope("2026-10", 19L));
        assertThat(all.getParameterMappings()).extracting(p -> p.getProperty()).containsExactly("month");
        assertThat(one.getParameterMappings()).extracting(p -> p.getProperty()).containsExactly("month", "projectId");
        assertThat(all.getSql()).contains("FROM cst.cst_aloc").doesNotContain("mth_cst", "drct_expns e", "coalesce");
    }
    @Test void projectRevenueUsesSameBillingMonthRuleAndOnlyConfirmedSupply() throws Exception {
        var c = configuration();
        var scope = new com.sicost.billingsops.BillingModels.Scope(19, "2026-10", null);
        var bound = c.getMappedStatement(BillingMapper.class.getName() + ".projectRevenue").getBoundSql(scope);
        assertThat(bound.getSql()).contains("sum(b.sply_amt)", "b.bill_stts_cd='CONFIRM'", "b.prj_id=?", "p.cntrct_ty_cd='TK'", "b.atrb_ym")
            .doesNotContain("rcpt", "vat_amt", "bill_dtl", "mm_qty");
        assertThat(bound.getParameterMappings()).extracting(p -> p.getProperty()).containsExactly("projectId", "month");
    }
}
