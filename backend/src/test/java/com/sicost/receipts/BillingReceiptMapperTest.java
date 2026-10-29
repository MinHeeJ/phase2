package com.sicost.receipts;

import java.util.Map;
import java.util.regex.Pattern;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.receipts.BillingReceiptModels.*;
import static org.assertj.core.api.Assertions.*;

class BillingReceiptMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true);
        c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/receipts/BillingReceiptMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, c, path, c.getSqlFragments()).parse();
        }
        return c;
    }
    String sql(Configuration c, String method, Object input) {
        return c.getMappedStatement(BillingReceiptMapper.class.getName() + "." + method)
            .getBoundSql(input).getSql();
    }
    @Test void everyProjectionMatchesRecordComponentLabelTypeAndOrderIncludingNullableJoins() throws Exception {
        var c = configuration();
        var records = Map.of("state", BillState.class, "header", Header.class, "totals", Totals.class,
            "receipt", Receipt.class, "detail", DetailLine.class, "change", Change.class);
        var selects = Map.of("state", "lockBill", "header", "header", "totals", "totals",
            "receipt", "list", "detail", "details", "change", "history");
        for (var entry : records.entrySet()) {
            var projection = c.getResultMap(BillingReceiptMapper.class.getName() + "." + entry.getKey());
            assertThat(projection.getType()).isEqualTo(entry.getValue());
            var components = entry.getValue().getRecordComponents();
            var mappings = projection.getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            String selected = sql(c, selects.get(entry.getKey()), 1L).split("(?i)\\bFROM\\b", 2)[0];
            // Totals contains scalar subqueries: its outer aliases follow those subqueries.
            if (entry.getKey().equals("totals")) selected = sql(c, "totals", 1L);
            for (int i = 0; i < components.length; i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
                assertThat(Pattern.compile("(?i)\\b" + Pattern.quote(components[i].getName()) + "\\b")
                    .matcher(selected).find()).as(entry.getKey() + "." + components[i].getName()).isTrue();
            }
        }
        assertThat(Header.class.getRecordComponents()[1].getType()).isEqualTo(Long.class);
        assertThat(DetailLine.class.getRecordComponents()[0].getType()).isEqualTo(Long.class);
        for (var method : BillingReceiptMapper.class.getDeclaredMethods())
            assertThat(c.hasStatement(BillingReceiptMapper.class.getName() + "." + method.getName())).isTrue();
    }
    @Test void rootLockAndBalanceQueryUseExactSchemaAndConfirmedAdjustmentsOnly() throws Exception {
        var c = configuration();
        assertThat(sql(c, "lockBill", 1L)).contains("FROM bill.bill b", "FOR UPDATE");
        assertThat(sql(c, "totals", 1L)).contains("a.orgnl_bill_id=b.bill_id", "a.amnd_yn='Y'",
            "a.bill_stts_cd='CONFIRM'", "SUM(r.rcpt_amt)").doesNotContain("vw_bill_unrcv", "CURRENT_DATE");
        assertThat(sql(c, "details", 1L)).contains("a.dev_id", "d.cntrct_dev_id").doesNotContain("d.dev_id");
    }
    @Test void writesAreScopedAndNeverTouchImmutableBillingOrNonexistentAuditColumns() throws Exception {
        var c = configuration();
        var input = new ReceiptInput(null, java.time.LocalDate.of(2026, 10, 1), 30, "입금");
        String insert = sql(c, "insert", new ReceiptWrite(1, 0, input, null));
        String update = sql(c, "update", new ReceiptWrite(1, 7, input, null));
        String delete = sql(c, "delete", new ReceiptKey(1, 7));
        assertThat(insert).contains("RETURNING rcpt_id");
        assertThat(update).contains("WHERE bill_id=? AND rcpt_id=?").doesNotContain("updated_at", "updated_by");
        assertThat(delete).contains("WHERE bill_id=? AND rcpt_id=?");
        assertThat(insert + update + delete).doesNotContain("UPDATE bill.bill", "atrb_ym=", "sply_amt=");
        String audit = sql(c, "audit", new ReceiptAudit("1", "7", "DELETE", "{}", null, null));
        assertThat(audit).contains("bfr_val", "aft_val", "chg_user_id", "rsn_desc");
    }
}
