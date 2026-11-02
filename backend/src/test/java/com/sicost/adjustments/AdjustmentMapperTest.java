package com.sicost.adjustments;

import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.adjustments.AdjustmentModels.*;

class AdjustmentMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();String path="mappers/adjustments/AdjustmentMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()) {new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}
        return c;
    }
    @Test void everyMapperMethodHasAnXmlStatement() throws Exception {
        var c=configuration();
        for(var method:AdjustmentMapper.class.getDeclaredMethods())
            assertThat(c.hasStatement(AdjustmentMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
    }
    @Test void rowConstructorMatchesNullableSqlTypes() throws Exception {
        var mappings=configuration().getResultMap(AdjustmentMapper.class.getName()+".adjustmentRow").getConstructorResultMappings();
        assertThat(mappings).hasSize(Row.class.getRecordComponents().length);
        var fields=Row.class.getRecordComponents();
        for(int i=0;i<fields.length;i++)assertThat(mappings.get(i).getJavaType()).isEqualTo(fields[i].getType());
    }
    @Test void insertUsesRecordPropertiesAndGeneratedIdNotListReadback() throws Exception {
        var c=configuration();var write=new Write(new Input("2026-08","2026-10","REVENUE",100,"사유",null,null,null),null,LocalDateTime.parse("2026-10-29T09:00:00"));
        var sql=c.getMappedStatement(AdjustmentMapper.class.getName()+".insert").getBoundSql(write);
        for(var p:sql.getParameterMappings())assertThatCode(()->c.newMetaObject(write).getValue(p.getProperty())).doesNotThrowAnyException();
        assertThat(sql.getSql()).contains("RETURNING adj_id","'ACTIVE'");assertThat(sql.getSql()).doesNotContain("ON CONFLICT");
    }
    @Test void listAndCountShareIdenticalOptionalPredicates() throws Exception {
        var c=configuration();
        for(String month:new String[]{null,"2026-10"}) {
            var q=new Query(2026,month,1,7,7);
            var list=c.getMappedStatement(AdjustmentMapper.class.getName()+".list").getBoundSql(q);
            var count=c.getMappedStatement(AdjustmentMapper.class.getName()+".count").getBoundSql(q);
            String listWhere=list.getSql().split("WHERE",2)[1].split("ORDER BY",2)[0].replaceAll("\\s+"," ").trim();
            String countWhere=count.getSql().split("WHERE",2)[1].replaceAll("\\s+"," ").trim();
            assertThat(listWhere).isEqualTo(countWhere);assertThat(listWhere).doesNotContain("IS NULL");
            for(var p:list.getParameterMappings())assertThatCode(()->c.newMetaObject(q).getValue(p.getProperty())).doesNotThrowAnyException();
        }
    }
    @Test void cancellationUpdatesOnlyStateAndCancellationMetadata() throws Exception {
        var c=configuration();var cancel=new Cancel(7,"취소",null,LocalDateTime.parse("2026-10-29T09:00:00"));
        String sql=c.getMappedStatement(AdjustmentMapper.class.getName()+".cancel").getBoundSql(cancel).getSql();
        assertThat(sql).contains("adj_stts_cd='CANCELLED'","cncl_rsn_desc=","cncl_user_id=","cncl_at=","adj_stts_cd='ACTIVE'");
        assertThat(sql).doesNotContain("adj_amt=","orgnl_ym=","aply_ym=","created_at=");
        assertThat(sql).doesNotContainPattern("(?:SET|,)\\s*rsn_desc\\s*=");
        assertThat(c.getMappedStatement(AdjustmentMapper.class.getName()+".lockMonths").getBoundSql(null).getSql()).contains("SHARE ROW EXCLUSIVE");
    }
}
