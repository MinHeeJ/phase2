package com.sicost.common;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sicost.common.billing.*;
import com.sicost.common.billing.BillingSupportModels.*;
import com.sicost.common.billing.BillingSupportModels.Period;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BillingMapperTest {
    Configuration configuration() throws Exception {
        var configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setArgNameBasedConstructorAutoMapping(true);
        for (String file : List.of("BillingRevenueQuery.xml", "BillingMutationMapper.xml")) {
            String path = "mappers/common/" + file;
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        return configuration;
    }
    String sql(Class<?> mapper, String method, Object parameter) throws Exception {
        return configuration().getMappedStatement(mapper.getName() + "." + method)
            .getBoundSql(parameter).getSql().replaceAll("\\s+", " ").trim();
    }
    Object materialize(Class<?> mapper, String method, LinkedHashMap<String, Object> row) throws Exception {
        var configuration = configuration();
        var statement = configuration.getMappedStatement(mapper.getName() + "." + method);
        var mappings = configuration.getResultMap(statement.getResultMaps().get(0).getId()).getConstructorResultMappings();
        var components = statement.getResultMaps().get(0).getType().getRecordComponents();
        assertThat(mappings).hasSize(components.length);
        for (int i = 0; i < components.length; i++) {
            assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
            assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
        }
        var metadata = mock(ResultSetMetaData.class);
        var result = mock(ResultSet.class);
        var jdbc = mock(Statement.class);
        when(jdbc.getResultSet()).thenReturn(result);
        when(jdbc.getMoreResults()).thenReturn(false);
        when(jdbc.getUpdateCount()).thenReturn(-1);
        when(result.getMetaData()).thenReturn(metadata);
        when(result.next()).thenReturn(true, false);
        when(result.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        when(metadata.getColumnCount()).thenReturn(row.size());
        int index = 0;
        for (var entry : row.entrySet()) {
            int column = ++index;
            String label = entry.getKey(); Object value = entry.getValue();
            when(metadata.getColumnLabel(column)).thenReturn(label);
            when(metadata.getColumnName(column)).thenReturn(label);
            when(metadata.getColumnType(column)).thenReturn(value instanceof Number ? Types.BIGINT : Types.VARCHAR);
            when(metadata.getColumnClassName(column)).thenReturn(value.getClass().getName());
            when(result.getString(label)).thenReturn(value.toString());
            if (value instanceof Long number) when(result.getLong(label)).thenReturn(number);
            if (value instanceof LocalDate date) when(result.getObject(label, LocalDate.class)).thenReturn(date);
            if (value instanceof LocalDateTime date) when(result.getObject(label, LocalDateTime.class)).thenReturn(date);
        }
        var handler = new DefaultResultSetHandler(mock(Executor.class), statement, null, null,
            statement.getBoundSql(null), RowBounds.DEFAULT);
        return handler.handleResultSets(jdbc).get(0);
    }
    LinkedHashMap<String, Object> row(Object... values) {
        var row = new LinkedHashMap<String, Object>();
        for (int i = 0; i < values.length; i += 2) row.put((String) values[i], values[i + 1]);
        return row;
    }
    @Test void everyMapperMethodIsRegistered() throws Exception {
        var c = configuration();
        for (var mapper : List.of(BillingRevenueQuery.class, BillingMutationMapper.class)) {
            for (var method : mapper.getDeclaredMethods()) {
                assertThat(c.hasStatement(mapper.getName() + "." + method.getName())).isTrue();
            }
        }
    }
    @Test void revenueAndSupplyMaterializeThroughActualConstructorMapsWithoutDatabase() throws Exception {
        assertThat(materialize(BillingRevenueQuery.class, "monthlyRevenue",
            row("month", "2026-10", "confirmed_supply_amt", 123L))).isEqualTo(new Revenue("2026-10", 123));
        assertThat(materialize(BillingRevenueQuery.class, "confirmedSupply",
            row("confirmed_supply_amt", 321L, "confirmed_count", 3L))).isEqualTo(new Supply(321, 3));
    }
    @Test void receiptPhysicalIdProjectsToReceiptIdAndPreservesDateAndVatIncludedAmount() throws Exception {
        var date = LocalDate.of(2026, 11, 1);
        assertThat(materialize(BillingRevenueQuery.class, "cashReceipts",
            row("receipt_id", 19L, "bill_id", 37L, "rcpt_dt", date, "rcpt_amt", 110L)))
            .isEqualTo(new CashReceipt(19, 37, date, 110));
        assertThat(sql(BillingRevenueQuery.class, "cashReceipts", new Period("2026-11", "2026-11")))
            .contains("r.rcpt_id AS receipt_id", "r.rcpt_amt", "r.rcpt_dt >= ?", "r.rcpt_dt < ?")
            .doesNotContain("r.receipt_id", "LIMIT", "OFFSET");
    }
    @Test void balanceProjectionSeparatesOriginalAdjustmentAndReceipts() throws Exception {
        assertThat(materialize(BillingRevenueQuery.class, "balance", row("bill_id", 37L,
            "bill_stts_cd", "CONFIRM", "amnd_yn", "N", "original_total_amt", 1100L,
            "confirmed_adjustment_amt", -220L, "received_amt", 330L)))
            .isEqualTo(new Balance(37, "CONFIRM", "N", 1100, -220, 330));
    }
    @Test void stateProjectionMatchesEveryConstructorComponent() throws Exception {
        LocalDate date = LocalDate.of(2026, 10, 31);
        LocalDateTime created = LocalDateTime.of(2026, 11, 1, 0, 1);
        assertThat(materialize(BillingMutationMapper.class, "lockBill", row("bill_id", 38L, "prj_id", 37L,
            "orgnl_bill_id", 36L, "bill_stts_cd", "DRAFT", "contract_type", "MO", "atrb_ym", "2026-10",
            "bill_dt", date, "bill_plan_dt", date, "created_at", created)))
            .isEqualTo(new State(38, 37, 36L, "DRAFT", "MO", "2026-10", date, date, created));
    }
    @Test void financialQueriesOnlyAggregateConfirmedSupplyAndNeverReceiptDate() throws Exception {
        String revenue = sql(BillingRevenueQuery.class, "monthlyRevenue", new Period("2026-01", "2026-12"));
        assertThat(revenue).contains("b.bill_stts_cd='CONFIRM'", "SUM(b.sply_amt)",
            "to_char(b.bill_dt,'YYYY-MM') ELSE b.atrb_ym").doesNotContain("rcpt_dt", "SUM(b.tot_amt)");
        assertThat(sql(BillingRevenueQuery.class, "confirmedSupply", new ProjectMonth(37, "2026-10")))
            .contains("b.bill_stts_cd='CONFIRM'", "b.prj_id=?");
        assertThat(sql(BillingRevenueQuery.class, "balance", 37L)).contains("a.bill_stts_cd='CONFIRM'", "SUM(a.tot_amt)", "SUM(r.rcpt_amt)");
    }
    @Test void lockingAndAuditUseExistingTablesWithoutInventedColumns() throws Exception {
        assertThat(sql(BillingMutationMapper.class, "lockBill", 37L)).endsWith("FOR UPDATE OF b");
        assertThat(sql(BillingMutationMapper.class, "numberLock", "B-2611-"))
            .contains("pg_advisory_xact_lock(hashtext('bill.bill_no'),hashtext(?))");
        assertThat(sql(BillingMutationMapper.class, "audit", new Audit("bill.bill", "37", "UPDATE",
            "sply_amt", "100", "200", "정정", null))).contains("INSERT INTO sys.data_chg_hist", "rsn_desc,chg_user_id");
    }
}
