package com.sicost.board;

import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static com.sicost.board.WorkforceBoardModels.*;
import static org.assertj.core.api.Assertions.*;

class WorkforceBoardMapperTest {
    Configuration configuration() throws Exception {
        var c=new Configuration(); String path="mappers/board/WorkforceBoardMapper.xml";
        try(var input=new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input,c,path,c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void everyMethodAndConstructorIsRegistered() throws Exception {
        var c=configuration();
        for(var method:WorkforceBoardMapper.class.getDeclaredMethods())
            assertThat(c.hasStatement(WorkforceBoardMapper.class.getName()+"."+method.getName())).isTrue();
        for(var entry:Map.of("person",Person.class,"leave",Leave.class,"month",MonthlyAssignment.class,"temporary",TemporarySource.class).entrySet()) {
            var map=c.getResultMap(WorkforceBoardMapper.class.getName()+"."+entry.getKey());
            assertThat(map.getType()).isEqualTo(entry.getValue());
            assertThat(map.getConstructorResultMappings()).hasSize(entry.getValue().getRecordComponents().length);
        }
    }
    @Test void optionalPredicatesOnlyBindPresentFiltersAndNeverTruncateAllRows() throws Exception {
        var statement=configuration().getMappedStatement(WorkforceBoardMapper.class.getName()+".people");
        var all=statement.getBoundSql(WorkforceBoardInput.parse(Map.of("year","2026"),false));
        var filtered=statement.getBoundSql(WorkforceBoardInput.parse(Map.of("year","2026","job","BE","grade","S","skill","JAVA","emp_se_cd","FT"),false));
        assertThat(all.getSql()).doesNotContain("d.main_job_cd=?","d.grd_cd=?","s.skl_cd=?","LIMIT","OFFSET","? IS NULL");
        assertThat(filtered.getSql()).contains("d.main_job_cd=?","d.grd_cd=?","s.skl_cd=?","d.emp_se_cd=?");
        assertThat(filtered.getParameterMappings()).extracting(p -> p.getProperty())
            .containsExactly("end","job","grade","skill","emp_se_cd");
    }
}
