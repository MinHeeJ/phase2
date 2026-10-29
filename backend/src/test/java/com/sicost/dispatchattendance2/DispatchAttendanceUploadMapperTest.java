package com.sicost.dispatchattendance2;

import java.math.BigDecimal;
import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.mapping.*;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.dispatchattendance2.DispatchAttendanceUploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DispatchAttendanceUploadMapperTest {
    Configuration configuration() throws Exception {
        var config=new Configuration();
        config.setMapUnderscoreToCamelCase(true);config.setArgNameBasedConstructorAutoMapping(true);
        String path="mappers/dispatchattendance2/DispatchAttendanceUploadMapper.xml";
        try (var in=new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(in,config,path,config.getSqlFragments()).parse();
        }
        return config;
    }
    @Test void everyMethodAndConstructorHasExactColumnsTypesAndOrder() throws Exception {
        var config=configuration();String ns=DispatchAttendanceUploadMapper.class.getName()+".";
        for (var method:DispatchAttendanceUploadMapper.class.getDeclaredMethods()) assertThat(config.hasStatement(ns+method.getName())).isTrue();
        for (var entry:Map.of("project",Project.class,"source",Source.class,"stored",Stored.class).entrySet()) {
            var mapping=config.getResultMap(ns+entry.getKey()).getConstructorResultMappings();
            var components=entry.getValue().getRecordComponents();assertThat(mapping).hasSize(components.length);
            for (int i=0;i<components.length;i++) {
                assertThat(mapping.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mapping.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    Object materialize(String method,List<Object> values) throws Exception {
        var config=configuration();
        var statement=config.getMappedStatement(DispatchAttendanceUploadMapper.class.getName()+"."+method);
        var mappings=statement.getResultMaps().get(0).getConstructorResultMappings();
        var result=mock(ResultSet.class);var meta=mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(meta);when(meta.getColumnCount()).thenReturn(mappings.size());
        when(result.next()).thenReturn(true,false);
        for (int n=0;n<mappings.size();n++) {
            String label=mappings.get(n).getColumn();Object value=values.get(n);int i=n+1;
            when(meta.getColumnLabel(i)).thenReturn(label);when(meta.getColumnName(i)).thenReturn(label);
            when(meta.getColumnClassName(i)).thenReturn(mappings.get(n).getJavaType().getName());
            when(meta.getColumnType(i)).thenReturn(value instanceof BigDecimal?Types.DECIMAL:value instanceof Number?Types.BIGINT:value instanceof Boolean?Types.BOOLEAN:Types.VARCHAR);
            when(result.getString(label)).thenReturn(value==null?null:value.toString());
            when(result.getLong(label)).thenAnswer(inv->{when(result.wasNull()).thenReturn(value==null);return value==null?0L:((Number)value).longValue();});
            when(result.getBigDecimal(label)).thenAnswer(inv->(BigDecimal)value);
            when(result.getBoolean(label)).thenAnswer(inv->(Boolean)value);
        }
        var jdbc=mock(Statement.class);when(jdbc.getResultSet()).thenReturn(result);when(jdbc.getUpdateCount()).thenReturn(-1);
        var connection=mock(Connection.class);var database=mock(DatabaseMetaData.class);
        when(jdbc.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(database);
        var handler=new DefaultResultSetHandler(mock(Executor.class),statement,null,null,
            statement.getBoundSql(new Scope(1,"2026-10",java.time.LocalDate.of(2026,10,1),java.time.LocalDate.of(2026,10,31))),RowBounds.DEFAULT);
        return handler.handleResultSets(jdbc).get(0);
    }
    @Test void realMyBatisMaterializesEveryProjectionWithoutDatabase() throws Exception {
        assertThat(materialize("project",List.of("DP","HOUR","Y"))).isEqualTo(new Project("DP","HOUR","Y"));
        assertThat(materialize("sources",Arrays.asList(31L,41L,"D-41","인력",null,false)))
            .isEqualTo(new Source(31,41,"D-41","인력",null,false));
        assertThat(materialize("sources",List.of(32L,42L,"D-42","다른 인력",new BigDecimal("0.75"),true)))
            .isEqualTo(new Source(32,42,"D-42","다른 인력",new BigDecimal("0.75"),true));
        assertThat(materialize("lockAttendance",Arrays.asList(71L,81L,null,new BigDecimal("12.5"),
            new BigDecimal("100.1"),new BigDecimal("2.5"),new BigDecimal("21.0"),null)))
            .isEqualTo(new Stored(71,81,null,new BigDecimal("12.5"),new BigDecimal("100.1"),
                new BigDecimal("2.5"),new BigDecimal("21.0"),null));
    }
}
