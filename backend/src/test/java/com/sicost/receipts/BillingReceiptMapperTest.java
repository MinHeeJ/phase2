package com.sicost.receipts;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.sicost.receipts.BillingReceiptModels.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class BillingReceiptMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true); c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/receipts/BillingReceiptMapper.xml";
        try (var in = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    String sql(String method, Object parameter) throws Exception {
        return configuration().getMappedStatement(BillingReceiptMapper.class.getName()+"."+method)
            .getBoundSql(parameter).getSql().replaceAll("\\s+"," ").trim();
    }
    @Test void everyMethodRegisteredAndEveryProjectionMaterializesAllConstructorFields() throws Exception {
        var c = configuration();
        for (var method : BillingReceiptMapper.class.getDeclaredMethods()) {
            assertThat(c.hasStatement(BillingReceiptMapper.class.getName()+"."+method.getName())).isTrue();
        }
        for (String method : List.of("billing","amendments","list","row","details","history")) {
            var statement = c.getMappedStatement(BillingReceiptMapper.class.getName()+"."+method);
            var map = statement.getResultMaps().get(0);
            var fields = map.getType().getRecordComponents();
            var mappings = map.getConstructorResultMappings();
            assertThat(mappings).hasSize(fields.length);
            var metadata = mock(ResultSetMetaData.class);
            var result = mock(ResultSet.class); var jdbc = mock(Statement.class);
            when(jdbc.getResultSet()).thenReturn(result); when(jdbc.getMoreResults()).thenReturn(false);
            when(jdbc.getUpdateCount()).thenReturn(-1); when(result.getMetaData()).thenReturn(metadata);
            when(result.next()).thenReturn(true,false); when(result.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
            when(metadata.getColumnCount()).thenReturn(fields.length);
            var expected = new ArrayList<Object>();
            for (int i=0;i<fields.length;i++) {
                var field = fields[i]; String label = field.getName(); Class<?> type = field.getType();
                assertThat(mappings.get(i).getColumn()).isEqualTo(label);
                assertThat(mappings.get(i).getJavaType()).isEqualTo(type);
                Object value;
                if (type == long.class || type == Long.class) value = 37L+i;
                else if (type == Integer.class) value = 2;
                else if (type == boolean.class) value = true;
                else if (type == LocalDate.class) value = LocalDate.of(2026,11,2);
                else if (type == LocalDateTime.class) value = LocalDateTime.of(2026,11,2,13,14);
                else if (type == BigDecimal.class) value = new BigDecimal("1.25");
                else value = "값-"+label;
                expected.add(value);
                when(metadata.getColumnLabel(i+1)).thenReturn(label);
                when(metadata.getColumnName(i+1)).thenReturn(label);
                when(metadata.getColumnType(i+1)).thenReturn(value instanceof Number ? Types.NUMERIC : Types.VARCHAR);
                when(metadata.getColumnClassName(i+1)).thenReturn(value.getClass().getName());
                when(result.getString(label)).thenReturn(value.toString());
                if (value instanceof Long n) when(result.getLong(label)).thenReturn(n);
                if (value instanceof Integer n) when(result.getInt(label)).thenReturn(n);
                if (value instanceof Boolean b) when(result.getBoolean(label)).thenReturn(b);
                if (value instanceof BigDecimal b) when(result.getBigDecimal(label)).thenReturn(b);
                if (value instanceof LocalDate d) when(result.getObject(label,LocalDate.class)).thenReturn(d);
                if (value instanceof LocalDateTime d) when(result.getObject(label,LocalDateTime.class)).thenReturn(d);
            }
            var handler = new DefaultResultSetHandler(mock(Executor.class),statement,null,null,
                statement.getBoundSql(new ReceiptKey(1,2)),RowBounds.DEFAULT);
            Object mapped = handler.handleResultSets(jdbc).get(0);
            for (int i=0;i<fields.length;i++) {
                assertThat(fields[i].getAccessor().invoke(mapped)).as(method+"."+fields[i].getName()).isEqualTo(expected.get(i));
            }
        }
    }
    @Test void mutationsUseParentAssociationPhysicalIdAndNoInventedColumns() throws Exception {
        var key = new ReceiptKey(7,19);
        assertThat(sql("row",key)).contains("r.rcpt_id AS receipt_id","r.bill_id=? AND r.rcpt_id=?");
        assertThat(sql("delete",key)).isEqualTo("DELETE FROM bill.rcpt WHERE bill_id=? AND rcpt_id=?");
        var write = new ReceiptWrite(7,19L,new ReceiptInput(LocalDate.of(2026,11,1),30,"메모"),null);
        assertThat(sql("insert",write)).contains("RETURNING rcpt_id").doesNotContain("MAX(","updated_at","receipt_id");
        assertThat(sql("update",write)).contains("WHERE bill_id=? AND rcpt_id=?")
            .doesNotContain("updated_at","updated_by","bill.bill SET","atrb_ym");
    }
    @Test void balanceUsesConfirmedAmendmentsAndVatIncludedReceiptsAndHistoryKeepsDeletedRows() throws Exception {
        assertThat(sql("billing",7L)).contains("SUM(r.rcpt_amt)","SUM(a.tot_amt)","a.bill_stts_cd='CONFIRM'",
            "b.tot_amt+", "AS adjusted_total_amt", "AS receivable_amt");
        assertThat(sql("history",7L)).contains("h.rsn_desc=concat('청구 입금 #'", "h.bfr_val", "h.aft_val")
            .doesNotContain("JOIN bill.rcpt");
    }
}
