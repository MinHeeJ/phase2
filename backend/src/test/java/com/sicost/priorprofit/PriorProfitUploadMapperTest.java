package com.sicost.priorprofit;

import java.sql.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.executor.Executor;
import org.apache.ibatis.executor.parameter.ParameterHandler;
import org.apache.ibatis.executor.resultset.DefaultResultSetHandler;
import org.apache.ibatis.scripting.defaults.DefaultParameterHandler;
import org.apache.ibatis.session.*;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.priorprofit.PriorProfitUploadModels.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PriorProfitUploadMapperTest {
    Configuration configuration() throws Exception {
        var config=new Configuration();config.setMapUnderscoreToCamelCase(true);config.setArgNameBasedConstructorAutoMapping(true);
        String path="mappers/priorprofit/PriorProfitUploadMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()) {new XMLMapperBuilder(in,config,path,config.getSqlFragments()).parse();}
        return config;
    }
    @Test void everyMethodHasAnXmlStatementAndExactConstructorTypes() throws Exception {
        var config=configuration();String ns=PriorProfitUploadMapper.class.getName()+".";
        for(var method:PriorProfitUploadMapper.class.getDeclaredMethods()) assertThat(config.hasStatement(ns+method.getName())).isTrue();
        for(var entry:Map.of("snapshot",Snapshot.class,"detail",Detail.class).entrySet()) {
            var mapping=config.getResultMap(ns+entry.getKey()).getConstructorResultMappings();var components=entry.getValue().getRecordComponents();
            assertThat(mapping).hasSize(components.length);
            for(int i=0;i<components.length;i++) assertThat(mapping.get(i).getJavaType()).isEqualTo(components[i].getType());
        }
    }
    Object materialize(String method,List<Object> values) throws Exception {
        var config=configuration();var statement=config.getMappedStatement(PriorProfitUploadMapper.class.getName()+"."+method);
        var mappings=statement.getResultMaps().get(0).getConstructorResultMappings();
        var result=mock(ResultSet.class);var meta=mock(ResultSetMetaData.class);
        when(result.getMetaData()).thenReturn(meta);when(meta.getColumnCount()).thenReturn(mappings.size());when(result.next()).thenReturn(true,false);
        for(int n=0;n<mappings.size();n++) {
            String label=mappings.get(n).getColumn();Object value=values.get(n);int i=n+1;
            when(meta.getColumnLabel(i)).thenReturn(label);when(meta.getColumnName(i)).thenReturn(label);
            when(meta.getColumnClassName(i)).thenReturn(mappings.get(n).getJavaType().getName());when(meta.getColumnType(i)).thenReturn(value instanceof Number?Types.BIGINT:Types.VARCHAR);
            when(result.getString(label)).thenReturn(value==null?null:value.toString());
            when(result.getLong(label)).thenAnswer(inv->{when(result.wasNull()).thenReturn(value==null);return value==null?0L:((Number)value).longValue();});
        }
        var jdbc=mock(Statement.class);when(jdbc.getResultSet()).thenReturn(result);when(jdbc.getUpdateCount()).thenReturn(-1);
        var connection=mock(Connection.class);var database=mock(DatabaseMetaData.class);when(jdbc.getConnection()).thenReturn(connection);when(connection.getMetaData()).thenReturn(database);
        var handler=new DefaultResultSetHandler(mock(Executor.class),statement,null,null,statement.getBoundSql(null),RowBounds.DEFAULT);
        return handler.handleResultSets(jdbc).get(0);
    }
    @Test void realMyBatisMaterializesNullableReferenceAndSignedMoney() throws Exception {
        assertThat(materialize("snapshots",Arrays.asList("2025-01",100L,20L,1L,2L,3L,4L,5L,-6L,7L,8L,9L,null)))
            .isEqualTo(new Snapshot("2025-01",100,20,1,2,3,4,5,-6,7,8,9,null));
        assertThat(materialize("details",List.of("2025-01","RENT",100L))).isEqualTo(new Detail("2025-01","RENT",100));
    }
    @Test void allWriteParametersBindThroughRecordAccessorsAndNoGeneratedColumnIsWritten() throws Exception {
        var config=configuration();String ns=PriorProfitUploadMapper.class.getName()+".";
        Snapshot row=new Snapshot("2025-01",100,20,1,2,3,4,5,-6,7,8,9,null);
        var parsed=new Parsed(List.of(new Preview(5,"NORMAL",row)),List.of(),List.of());
        var review=new Review(1,"손익.xlsx","/upload/file.xlsx","digest",parsed);
        var summary=new Summary("1",0,List.of(),1,true,0,parsed.rows(),parsed.details(),"교체");
        Map<String,Object> writes=Map.of("insertSnapshot",new SnapshotWrite(row,1,null),"insertDetail",new Detail("2025-01","RENT",3),
            "saveReview",new ReviewWrite(review,summary,"APPLIED",null),"audit",new Audit("fin.mth_pl","2025-01","INSERT",null,"{}",null));
        for(var entry:writes.entrySet()) {
            var statement=config.getMappedStatement(ns+entry.getKey());
            ParameterHandler handler=new DefaultParameterHandler(statement,entry.getValue(),statement.getBoundSql(entry.getValue()));
            handler.setParameters(mock(PreparedStatement.class));
        }
        String sql=config.getMappedStatement(ns+"insertSnapshot").getBoundSql(writes.get("insertSnapshot")).getSql();
        assertThat(sql).doesNotContain("sls_cst_amt","grs_prft_amt","tot_sga_amt","bsn_prft_amt","net_prft_amt");
        String deletion=config.getMappedStatement(ns+"deleteImports").getBoundSql(null).getSql();
        assertThat(deletion).contains("pl_src_cd='IMPORT'","2025-01","2025-12");
    }
}
