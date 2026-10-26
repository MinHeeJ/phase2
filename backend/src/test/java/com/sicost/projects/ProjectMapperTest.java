package com.sicost.projects;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.projects.ProjectModels.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class ProjectMapperTest {
    Configuration configuration() throws Exception {
        var c = new Configuration(); String path = "mappers/projects/ProjectMapper.xml";
        try (var in = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(in,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    ProjectQuery query(Map<String,String> filters) { return ProjectQuery.of(filters,LocalDate.of(2026,10,23)); }
    String sql(Configuration c, String method, Object parameter) {
        return c.getMappedStatement(ProjectMapper.class.getName()+"."+method).getBoundSql(parameter).getSql().replaceAll("\\s+"," ").trim();
    }
    @Test void everyMethodRegisteredAndConstructorMapsMatchActualRecords() throws Exception {
        var c=configuration();
        for (var method : ProjectMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(ProjectMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        assertThat(c.getResultMap(ProjectMapper.class.getName()+".listRow").getConstructorResultMappings()).hasSize(ProjectListItem.class.getRecordComponents().length);
        assertThat(c.getResultMap(ProjectMapper.class.getName()+".projectRow").getConstructorResultMappings()).hasSize(ProjectRow.class.getRecordComponents().length);
    }
    @Test void listAndCountShareAllOptionalPredicatesBeforePage() throws Exception {
        var c=configuration();var q=query(Map.of("type","MO","status","END","part","NONE","q","이름","page","1","sort","prj_nm,asc"));
        String list=sql(c,"list",q),count=sql(c,"count",q);
        String predicates=list.substring(list.indexOf("WHERE p.use_yn"),list.lastIndexOf("ORDER BY"));
        assertThat(count).endsWith(predicates.trim());
        assertThat(list).contains("p.cntrct_ty_cd!=","p.bsn_part_id IS NULL","p.prj_stts_cd=", "ORDER BY \"prj_nm\" asc NULLS LAST, prj_id ASC LIMIT ? OFFSET ?");
        assertThat(list).doesNotContain("? IS NULL OR", "FOR UPDATE");
    }
    @Test void noticeIsYearOnlyAndDefaultStatusExcludesEndLost() throws Exception {
        var c=configuration();var q=query(Map.of("type","IN","status","END","part","1","q","검색"));
        assertThat(sql(c,"unassignedCount",q)).contains("p.use_yn='Y'","p.bsn_part_id IS NULL","p.cntrct_ty_cd!='IN'","coalesce(p.prfm_bgn_dt,p.cntrct_bgn_dt)")
            .doesNotContain("p.prj_stts_cd", "ptnr_nm", "LIMIT", "OFFSET");
        assertThat(sql(c,"list",query(Map.of()))).contains("p.prj_stts_cd NOT IN ('END','LOST')","count(DISTINCT a.dev_id)","a.asgn_stts_cd='ACTIVE'","a.bgn_dt <= ? AND a.end_dt >= ?");
    }
    @Test void absentFiltersNeverBindNullAndNamesRemainQuoted() throws Exception {
        var c=configuration();var statement=c.getMappedStatement(ProjectMapper.class.getName()+".list");
        var bound=statement.getBoundSql(query(Map.of()));
        assertThat(bound.getParameterMappings()).noneMatch(p -> Set.of("type","status","part","q").contains(p.getProperty()));
        assertThat(bound.getSql()).contains("AS \"custName\"","AS \"partName\"","AS \"assignmentCount\"");
    }
    @Test void updateOnlyEmitsServerChosenDifferencesAndNoPhaseWrites() throws Exception {
        String sql=sql(configuration(),"update",new ProjectUpdate(1,Map.of("prj_nm","수정"),null));
        assertThat(sql).contains("prj_nm=", "updated_by=").doesNotContain("prj_no=", "cur_cntrct_amt=", "bsn_part_id=", "prj.phs", "FOR UPDATE");
    }
    @Test void lockAndInsertUseExistingSequenceGeneratedKeyAndNullSourceDefaults() throws Exception {
        var c=configuration();assertThat(sql(c,"numberLock",null)).isEqualTo("SELECT pg_advisory_xact_lock(hashtext('prj.prj_no'))");
        var n=new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"prj_nm\":\"내부\",\"cntrct_ty_cd\":\"IN\",\"prj_stts_cd\":\"RUN\",\"cntrct_bgn_dt\":\"2026-01-01\",\"cntrct_end_dt\":\"2026-01-01\"}");
        String insert=sql(c,"insert",new ProjectWrite("P-2601",ProjectInput.parse(n,false),null));
        assertThat(insert).contains("RETURNING prj_id", "NULL ,'Y'").doesNotContain("pm_user_id", "oppt_id", "SELECT max(prj_id)");
    }
}
