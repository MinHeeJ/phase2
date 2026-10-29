package com.sicost.dispatchattendance;

import static com.sicost.dispatchattendance.DispatchAttendanceModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.RowBounds;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class DispatchAttendanceMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration();
        c.setMapUnderscoreToCamelCase(true); c.setArgNameBasedConstructorAutoMapping(true);
        String path = "mappers/dispatchattendance/DispatchAttendanceMapper.xml";
        try (var in = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    String sql(String method, Object parameter) throws Exception {
        return configuration().getMappedStatement(DispatchAttendanceMapper.class.getName()+"."+method)
            .getBoundSql(parameter).getSql().replaceAll("\\s+"," ").trim();
    }
    @Test void everyMethodRegisteredAndAllConstructorLabelsAndTypesExact() throws Exception {
        var c=configuration();
        for (var method:DispatchAttendanceMapper.class.getDeclaredMethods()) {
            assertThat(c.hasStatement(DispatchAttendanceMapper.class.getName()+"."+method.getName())).isTrue();
        }
        for (String name:List.of("project","source","input")) {
            var map=c.getResultMap(DispatchAttendanceMapper.class.getName()+"."+name);
            var components=map.getType().getRecordComponents();
            assertThat(map.getConstructorResultMappings()).hasSize(components.length);
            for (int i=0;i<components.length;i++) {
                assertThat(map.getConstructorResultMappings().get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(map.getConstructorResultMappings().get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    @Test void realMyBatisMaterializesEverySourceQuantityWithoutDatabase() throws Exception {
        var c=configuration(); var statement=c.getMappedStatement(DispatchAttendanceMapper.class.getName()+".list");
        var row=new Source(11,12,new BigDecimal("3.5"),new BigDecimal("28.5"),new BigDecimal("2.5"),
            new BigDecimal("21.5"),new BigDecimal("0.75"),true);
        var metadata=mock(ResultSetMetaData.class); var result=mock(ResultSet.class); var jdbc=mock(Statement.class);
        when(jdbc.getResultSet()).thenReturn(result); when(jdbc.getMoreResults()).thenReturn(false);
        when(jdbc.getUpdateCount()).thenReturn(-1); when(result.getMetaData()).thenReturn(metadata);
        when(result.next()).thenReturn(true,false); when(result.getType()).thenReturn(ResultSet.TYPE_FORWARD_ONLY);
        var components=Source.class.getRecordComponents(); when(metadata.getColumnCount()).thenReturn(components.length);
        for (int i=0;i<components.length;i++) {
            String label=components[i].getName(); Object value=components[i].getAccessor().invoke(row);
            when(metadata.getColumnLabel(i+1)).thenReturn(label); when(metadata.getColumnName(i+1)).thenReturn(label);
            when(metadata.getColumnType(i+1)).thenReturn(value instanceof BigDecimal?Types.NUMERIC:Types.BIGINT);
            when(metadata.getColumnClassName(i+1)).thenReturn(value.getClass().getName());
            if (value instanceof BigDecimal number) when(result.getBigDecimal(label)).thenReturn(number);
            if (value instanceof Long number) when(result.getLong(label)).thenReturn(number);
            if (value instanceof Boolean bool) when(result.getBoolean(label)).thenReturn(bool);
        }
        var handler=new DefaultResultSetHandler(mock(Executor.class),statement,null,null,
            statement.getBoundSql(null),RowBounds.DEFAULT);
        assertThat(handler.handleResultSets(jdbc)).containsExactly(row);
    }
    Scope scope() { return new Scope(1,"2026-10",LocalDate.of(2026,10,1),LocalDate.of(2026,10,31)); }
    @Test void queryUsesEffectivePriceAndAssignmentIntervalNotCurrentPriceOrCalendarGuess() throws Exception {
        assertThat(sql("list",scope())).contains("a.asgn_stts_cd!='CANCELLED'", "p.cntrct_ty_cd='DP'",
            "p.stlm_mthd_cd IN ('DAY','HOUR')", "a.bgn_dt <= ?", "a.end_dt >= ?",
            "h.aply_bgn_dt <= GREATEST(?,a.bgn_dt)", "ORDER BY h.aply_bgn_dt DESC LIMIT 1",
            "a.asgn_se_cd='FREE'", "m.mm_qty AS assignment_mm");
    }
    @Test void upsertNaturalKeyDoesNotTouchSourceOrDraftBilling() throws Exception {
        String text=sql("upsert",new Write(scope(),new Input(3,2,BigDecimal.ONE,null,BigDecimal.ZERO,BigDecimal.TEN),null));
        assertThat(text).contains("ON CONFLICT (work_ym,prj_id,dev_id)","RETURNING atnd_id")
            .doesNotContain("UPDATE asgn.","UPDATE bill.bill", "mm_qty=", "untprc_hist");
        assertThat(sql("billed",new Key(scope(),2))).contains("b.bill_stts_cd='CONFIRM'", "a.dev_id=?", "d.work_ym=?", "b.prj_id=?");
    }
}
