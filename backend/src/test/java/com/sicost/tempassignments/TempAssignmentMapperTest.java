package com.sicost.tempassignments;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.tempassignments.TempAssignmentModels.*;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class TempAssignmentMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration();String path="mappers/tempassignments/TempAssignmentMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()) {new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();}return c;
    }
    String sql(Configuration c,String method,Object parameter) {
        return c.getMappedStatement(TempAssignmentMapper.class.getName()+"."+method).getBoundSql(parameter).getSql().replaceAll("\\s+"," ").trim();
    }
    @Test void everyMethodAndRecordConstructorRegistered() throws Exception {
        var c=configuration();for(var method:TempAssignmentMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(TempAssignmentMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        assertThat(c.getResultMap(TempAssignmentMapper.class.getName()+".row").getConstructorResultMappings()).hasSize(Row.class.getRecordComponents().length);
        assertThat(c.getResultMap(TempAssignmentMapper.class.getName()+".need").getConstructorResultMappings()).hasSize(Need.class.getRecordComponents().length);
    }
    @Test void listAndCountUseExactlySameFilters() throws Exception {
        var c=configuration();var q=TempAssignmentInput.query(Map.of("need_id","11","dev_id","21","oppt_id","31","status","ACTIVE","from","2026-12","to","2027-01","sort","mth_mm_qty,desc","page","1"));
        String list=sql(c,"list",q), count=sql(c,"count",q);
        assertThat(count.substring(count.indexOf("WHERE"))).isEqualTo(list.substring(list.indexOf("WHERE"),list.lastIndexOf("ORDER BY")).trim());
        assertThat(list).contains("t.need_id=?","t.dev_id=?","n.oppt_id=?","t.mm_qty AS mth_mm_qty","ORDER BY t.mm_qty desc NULLS LAST");
    }
    @Test void absentFiltersNeverBindNull() throws Exception {
        var c=configuration();var q=TempAssignmentInput.query(Map.of());var statement=c.getMappedStatement(TempAssignmentMapper.class.getName()+".list");
        assertThat(statement.getBoundSql(q).getParameterMappings()).noneMatch(p -> Set.of("from","to","need_id","dev_id","oppt_id","status").contains(p.getProperty()));
        assertThat(sql(c,"list",q)).doesNotContain("IS NULL OR","FOR UPDATE");
    }
    @Test void concurrencyUsesDatabaseRowsAndInclusiveActiveOverlap() throws Exception {
        var c=configuration();assertThat(sql(c,"lockOpportunity",11L)).contains("bsn.oppt", "FOR UPDATE");assertThat(sql(c,"lockNeed",11L)).contains("bsn.oppt_need", "FOR UPDATE");
        String duplicate=sql(c,"duplicate",new Write(11,21,"2026-12","2027-01",java.math.BigDecimal.ONE,"MANUAL"));
        assertThat(duplicate).contains("tmpr_asgn_stts_cd='ACTIVE'", "t.bgn_ym <= ?", ">= ?");
        assertThat(sql(c,"release",new Release(9,null))).contains("tmpr_asgn_stts_cd='RELEASED'","tmpr_asgn_stts_cd='ACTIVE'").doesNotContain("DELETE", "asgn.asgn ");
    }
    @Test void insertReturnsOwnGeneratedKeyAndOnlyTemporaryTable() throws Exception {
        var c=configuration();var input=new Write(11,21,"2026-12","2027-01",java.math.BigDecimal.ONE,"MANUAL");
        assertThat(sql(c,"insert",new Insert(input,null,null))).contains("INSERT INTO asgn.tmpr_asgn", "mm_qty", "RETURNING tmpr_asgn_id").doesNotContain("asgn.asgn_mth","bill.","cst.","SELECT max");
    }
}
