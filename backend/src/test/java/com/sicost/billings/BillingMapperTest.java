package com.sicost.billings;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.billings.BillingModels.*;

class BillingMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/billings/BillingMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
        }
        return c;
    }
    String sql(Configuration c, String name, Object input) {
        return c.getMappedStatement(BillingMapper.class.getName() + "." + name).getBoundSql(input).getSql();
    }
    @Test void everyMethodAndEveryConstructorColumnHasExactRecordNameTypeAndOrder() throws Exception {
        var c = configuration();
        for (var method : BillingMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(BillingMapper.class.getName() + "." + method.getName())).isTrue();
        var models = Map.of("billingRow", BillingRow.class, "state", State.class, "project", Project.class,
            "stage", Stage.class, "source", Source.class, "detailLine", DetailLine.class, "receipt", Receipt.class, "history", History.class);
        for (var entry : models.entrySet()) {
            var map = c.getResultMap(BillingMapper.class.getName() + "." + entry.getKey());
            var mappings = map.getConstructorResultMappings();
            var fields = entry.getValue().getRecordComponents();
            assertThat(mappings).hasSize(fields.length);
            for (int i = 0; i < fields.length; i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(fields[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(fields[i].getType());
            }
        }
    }
    @Test void listAndCountUseExactlySameFilteredUnionWithoutNullPredicates() throws Exception {
        var c = configuration();
        var q = BillingQuery.parse(Map.of("year", "2026", "status", "DRAFT", "type", "MO", "q", "검색", "customerId", "5"), java.time.Clock.systemUTC());
        String list = sql(c, "list", q), count = sql(c, "count", q);
        assertThat(list.substring(list.indexOf("FROM ("), list.lastIndexOf("ORDER BY x.")).replaceAll("\\s+", " ").trim())
            .isEqualTo(count.substring(count.indexOf("FROM (")).replaceAll("\\s+", " ").trim());
        assertThat(list).contains("NOT EXISTS", "b.amnd_yn='N'", "b.bill_stts_cd!='CANCEL'", "x.customer_id=?", "x.bill_stts_cd=?")
            .doesNotContain("IS NULL OR", "INSERT", "CURRENT_DATE");
    }
    @Test void queryLocksAndEffectivePriceAreDatabaseBacked() throws Exception {
        var c = configuration();
        assertThat(sql(c, "lockClosingSources", null)).isEqualTo("LOCK TABLE fin.mth_cls IN SHARE MODE");
        assertThat(sql(c, "numberLock", "B-2610-")).contains("pg_advisory_xact_lock");
        assertThat(sql(c, "lockProject", 1)).contains("FOR UPDATE");
        assertThat(sql(c, "lockBill", 1)).contains("FOR UPDATE OF b");
        String source = sql(c, "sources", new SourceScope(1, "2026-10", java.time.LocalDate.of(2026, 10, 1), java.time.LocalDate.of(2026, 10, 31)));
        assertThat(source).contains("h.aply_bgn_dt <=greatest", "ORDER BY h.aply_bgn_dt DESC LIMIT 1", "a.asgn_stts_cd!='CANCELLED'")
            .doesNotContain("dflt_untprc_amt", "UPDATE", "INSERT");
    }
    @Test void insertUsesReturningIdentityAndNeverWritesGeneratedOrInventedColumns() throws Exception {
        var c = configuration();
        var s = BillingDetailApiTest.state("MO", "DRAFT", "2026-10", null);
        String insert = sql(c, "insert", new Write(0, "B-2610-01", s, null));
        assertThat(insert).contains("RETURNING bill_id").doesNotContain("tot_amt", "dev_id");
        assertThat(sql(c, "insertDetail", new DetailWrite(7, BillingDetailApiTest.line()))).contains("cntrct_dev_id", "work_ym").doesNotContain(",dev_id");
        assertThat(sql(c, "update", new Write(7, null, s, null))).contains("AND bill_stts_cd='DRAFT'").doesNotContain("prj_id=", "atrb_ym=", "bill_stg_id=");
    }
    @Test void detailHistoryIncludesReceiptAuditLinks() throws Exception {
        assertThat(sql(configuration(), "history", 7))
            .contains("h.tbl_nm='bill.bill'", "h.tbl_nm='bill.rcpt'", "h.rsn_desc='bill_id=' || ?::text");
    }
    @Test void scopedReadsBindEveryUnionBranchAndLockedReadUsesTheSamePredicate() throws Exception {
        var scope = new com.sicost.common.security.ScopePredicate(30, 8L, List.of(
            new com.sicost.common.security.ScopePredicate.Grant(4, com.sicost.common.security.DataScope.PART, Set.of(10L), Set.of(), false, false),
            new com.sicost.common.security.ScopePredicate.Grant(5, com.sicost.common.security.DataScope.PROJECT, Set.of(), Set.of(1L, 2L), false, false),
            new com.sicost.common.security.ScopePredicate.Grant(6, com.sicost.common.security.DataScope.SELF, Set.of(), Set.of(), false, false)));
        var c = configuration(); var access = new BillingAccess(7, scope);
        var read = sql(c, "scopedState", access).replaceAll("\\s+", " ").trim();
        var locked = sql(c, "lockScopedBill", access).replaceAll("\\s+", " ").trim();
        assertThat(read).contains("p.bsn_part_id IN", "OR p.prj_id IN", "OR p.pm_user_id=?", "OR p.pm_dev_id=?")
            .doesNotContain("OR TRUE", "IS NULL OR");
        assertThat(locked).isEqualTo(read + " FOR UPDATE OF b");
        var statement = c.getMappedStatement(BillingMapper.class.getName() + ".scopedState").getBoundSql(access);
        var parameters = c.newMetaObject(access);
        for (var binding : statement.getParameterMappings()) {
            String name = binding.getProperty();
            assertThat(statement.hasAdditionalParameter(name) || parameters.hasGetter(name)).as(name).isTrue();
        }
    }
    @Test void emptyScopeFailsClosedWithoutInvalidInClause() throws Exception {
        var scope = new com.sicost.common.security.ScopePredicate(30, null, List.of(
            new com.sicost.common.security.ScopePredicate.Grant(4, com.sicost.common.security.DataScope.PART, Set.of(), Set.of(), false, false)));
        assertThat(sql(configuration(), "scopedState", new BillingAccess(7, scope)))
            .contains("AND (FALSE").doesNotContain(" IN", "OR TRUE");
    }
}
