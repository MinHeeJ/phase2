package com.sicost.billings;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.sicost.billings.BillingModels.*;
import java.math.BigDecimal;
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
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true); c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/billings/BillingMapper.xml";
        try (var in = new ClassPathResource(path).getInputStream()) { new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse(); }
        return c;
    }
    String sql(String method, Object input) throws Exception {
        return configuration().getMappedStatement(BillingMapper.class.getName()+"."+method).getBoundSql(input).getSql().replaceAll("\\s+"," ").trim();
    }
    @Test void allMethodsHaveStatementsAndEveryProjectionMaterializesThroughRealConstructorMap() throws Exception {
        var c = configuration();
        for (var method : BillingMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(BillingMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        for (String method : List.of("row","header","project","stage","details","sources","receipts","history")) {
            var statement = c.getMappedStatement(BillingMapper.class.getName()+"."+method);
            var mapping = statement.getResultMaps().get(0);
            var fields = mapping.getType().getRecordComponents();
            var args = mapping.getConstructorResultMappings();
            assertThat(args).hasSize(fields.length);
            var metadata = mock(ResultSetMetaData.class); var result = mock(ResultSet.class); var jdbc = mock(Statement.class);
            when(jdbc.getResultSet()).thenReturn(result); when(jdbc.getMoreResults()).thenReturn(false); when(jdbc.getUpdateCount()).thenReturn(-1);
            when(result.getMetaData()).thenReturn(metadata); when(result.next()).thenReturn(true,false); when(result.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
            when(metadata.getColumnCount()).thenReturn(fields.length);
            Object[] values = new Object[fields.length]; Class<?>[] types = new Class<?>[fields.length];
            for (int i=0;i<fields.length;i++) {
                var field = fields[i]; String name = field.getName(); Class<?> type = field.getType();
                assertThat(args.get(i).getColumn()).isEqualTo(name); assertThat(args.get(i).getJavaType()).isEqualTo(type);
                Object value = type == long.class || type == Long.class ? 37L : type == int.class || type == Integer.class ? 3
                    : type == boolean.class ? true : type == BigDecimal.class ? new BigDecimal("1.25")
                    : type == LocalDate.class ? LocalDate.of(2026,10,31) : type == LocalDateTime.class ? LocalDateTime.of(2026,10,31,12,30) : name+"-값";
                values[i]=value; types[i]=type;
                when(metadata.getColumnLabel(i+1)).thenReturn(name); when(metadata.getColumnName(i+1)).thenReturn(name);
                when(metadata.getColumnClassName(i+1)).thenReturn(value.getClass().getName()); when(metadata.getColumnType(i+1)).thenReturn(Types.VARCHAR);
                when(result.getString(name)).thenReturn(value.toString());
                if(value instanceof Long n) when(result.getLong(name)).thenReturn(n);
                if(value instanceof Integer n) when(result.getInt(name)).thenReturn(n);
                if(value instanceof Boolean n) when(result.getBoolean(name)).thenReturn(n);
                if(value instanceof BigDecimal n) when(result.getBigDecimal(name)).thenReturn(n);
                if(value instanceof LocalDate n) when(result.getObject(name,LocalDate.class)).thenReturn(n);
                if(value instanceof LocalDateTime n) when(result.getObject(name,LocalDateTime.class)).thenReturn(n);
            }
            Object parameter = method.equals("sources") ? new SourceKey(1,"2026-10",LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)) : 1L;
            var handler = new DefaultResultSetHandler(mock(Executor.class),statement,null,null,statement.getBoundSql(parameter),RowBounds.DEFAULT);
            assertThat(handler.handleResultSets(jdbc).get(0)).isEqualTo(mapping.getType().getDeclaredConstructor(types).newInstance(values));
        }
    }
    @Test void listAndCountShareIdenticalOptionalPredicatesAndStableSafeSort() throws Exception {
        Query q = BillingInput.query(Map.of("type","MO","customerId","3","status","DRAFT","q","이름","sort","sply_amt,asc"),LocalDate.of(2026,10,31));
        String list=sql("list",q),count=sql("count",q);
        String predicates=list.substring(list.lastIndexOf("WHERE v.effective_date"),list.lastIndexOf("ORDER BY"));
        assertThat(count).endsWith(predicates.trim());
        assertThat(list).contains("v.customer_id=?","v.bill_stts_cd=?","ORDER BY v.sply_amt ASC NULLS LAST,v.bill_id ASC NULLS LAST,v.bill_stg_id ASC").doesNotContain("? IS NULL OR");
    }
    @Test void virtualPlansHaveNullIdsAndDatesAndYearUsesStagePlannedDateFallback() throws Exception {
        String list=sql("list",BillingInput.query(Map.of(),LocalDate.of(2026,10,31)));
        assertThat(list).contains("NULL::bigint AS bill_id","true AS virtual_plan","NULL::date AS bill_dt","coalesce(s.bill_plan_dt,s.created_at::date)","b.bill_stts_cd!='CANCEL'");
    }
    @Test void writesNeverMutateIdentityTotalOrSourcesAndUseReturnedKey() throws Exception {
        Header h=new Header(1,2,3L,1,null,null,100,10,null,null,null,null,"N",null);
        String insert=sql("insert",new Write("B-2610-100",h,null)), update=sql("update",new Write(null,h,null));
        assertThat(insert).contains("RETURNING bill_id").doesNotContain("tot_amt");
        assertThat(update).contains("bill_stts_cd='DRAFT'").doesNotContain("tot_amt=","prj_id=","atrb_ym=","bill_stg_id=");
        assertThat(sql("receipts",1L)).contains("r.rcpt_id AS receipt_id").doesNotContain("r.receipt_id");
        assertThat(sql("sources",new SourceKey(2,"2026-10",LocalDate.of(2026,10,1),LocalDate.of(2026,10,31))))
            .contains("greatest(?,a.bgn_dt)","a.asgn_stts_cd!='CANCELLED'","ORDER BY h.aply_bgn_dt DESC LIMIT 1");
    }
}
