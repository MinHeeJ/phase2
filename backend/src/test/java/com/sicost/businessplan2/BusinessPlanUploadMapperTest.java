package com.sicost.businessplan2;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.businessplan2.BusinessPlanUploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BusinessPlanUploadMapperTest {
    Configuration configuration()throws Exception {
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.setArgNameBasedConstructorAutoMapping(true);
        String path="mappers/businessplan2/BusinessPlanUploadMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()) {new XMLMapperBuilder(in,config,path,config.getSqlFragments()).parse();}
        return config;
    }
    @Test void allStatementsExistAndRecordArgumentsReallyBind()throws Exception {
        var config=configuration();String ns=BusinessPlanUploadMapper.class.getName()+".";
        for(var method:BusinessPlanUploadMapper.class.getDeclaredMethods()) assertThat(config.hasStatement(ns+method.getName())).as(method.getName()).isTrue();
        var row=new Plan(3,"2027-01",7L,100,-10,new BigDecimal("75.25"));
        var parsed=new Parsed("2027",List.of(new Preview(5,"NORMAL",row)),List.of());
        var review=new Review(100,"2027","사업계획.xlsx","/generated/file.xlsx","checksum",parsed);
        var summary=new Summary("100",0,List.of(),1,true,0,parsed.rows(),"미마감 월 전체 교체");
        Map<String,Object> writes=Map.of("replace",new PlanWrite(row,100,null),"deleteOpen",row,
            "saveReview",new ReviewWrite(review,summary,"APPLIED",null),"audit",new Audit("3","INSERT",null,"{}",null));
        for(var entry:writes.entrySet()) {
            var statement=config.getMappedStatement(ns+entry.getKey());var bound=statement.getBoundSql(entry.getValue());
            new DefaultParameterHandler(statement,entry.getValue(),bound).setParameters(mock(PreparedStatement.class));
        }
        assertThat(config.getMappedStatement(ns+"replace").getBoundSql(writes.get("replace")).getSql())
            .contains("RETURNING biz_plan_id","substring(?,1,4)");
        assertThat(config.getMappedStatement(ns+"deleteOpen").getBoundSql(row).getSql()).contains("NOT EXISTS","CLOSED");
    }
    @Test void realMyBatisMaterializesNullableCompanyPartAndExactLongAmounts()throws Exception {
        var config=configuration();var mapped=config.getMappedStatement(BusinessPlanUploadMapper.class.getName()+".plans");
        var mappings=mapped.getResultMaps().get(0).getConstructorResultMappings();
        var expected=new Plan(3,"2027-01",null,Long.MAX_VALUE,-100,new BigDecimal("75.25"));
        List<Object> values=Arrays.asList(3L,"2027-01",null,Long.MAX_VALUE,-100L,new BigDecimal("75.25"));
        var result=mock(ResultSet.class);var meta=mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(meta);when(meta.getColumnCount()).thenReturn(values.size());when(result.next()).thenReturn(true,false);
        for(int n=0;n<values.size();n++) {
            var mapping=mappings.get(n);String label=mapping.getColumn();Object value=values.get(n);int column=n+1;
            when(meta.getColumnLabel(column)).thenReturn(label);when(meta.getColumnName(column)).thenReturn(label);
            when(meta.getColumnClassName(column)).thenReturn(mapping.getJavaType().getName());
            when(meta.getColumnType(column)).thenReturn(value instanceof BigDecimal?Types.DECIMAL:value instanceof Number?Types.BIGINT:Types.VARCHAR);
            when(result.getString(label)).thenReturn(value==null?null:value.toString());
            when(result.getLong(label)).thenAnswer(i->{when(result.wasNull()).thenReturn(value==null);return value==null?0L:((Number)value).longValue();});
            when(result.getBigDecimal(label)).thenAnswer(i->(BigDecimal)value);
        }
        var jdbc=mock(Statement.class);when(jdbc.getResultSet()).thenReturn(result);when(jdbc.getUpdateCount()).thenReturn(-1);
        var connection=mock(Connection.class);var database=mock(DatabaseMetaData.class);when(jdbc.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(database);
        var handler=new DefaultResultSetHandler(mock(Executor.class),mapped,null,null,mapped.getBoundSql("2027"),RowBounds.DEFAULT);
        assertThat(handler.handleResultSets(jdbc)).containsExactly(expected);
    }
}
