package com.sicost.apiops;

import static org.assertj.core.api.Assertions.*;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class UploadResultMapperTest {
    @Test void mapperUsesOnlyTargetMonthAndHasNoBusinessWriteStatements() throws Exception {
        Configuration configuration = new Configuration();
        String resource = "mappers/uploads/UploadResultMapper.xml";
        try (var input = new ClassPathResource(resource).getInputStream()) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
        for (var method : UploadResultMapper.class.getDeclaredMethods()) {
            assertThat(configuration.hasStatement(UploadResultMapper.class.getName() + "." + method.getName())).isTrue();
        }
        var statement = configuration.getMappedStatement(UploadResultMapper.class.getName() + ".existingSalaryCount");
        var sql = statement.getBoundSql(Map.of("month", "2026-10"));
        assertThat(statement.getSqlCommandType()).isEqualTo(org.apache.ibatis.mapping.SqlCommandType.SELECT);
        assertThat(sql.getSql()).contains("count(*)", "cst.mth_cst", "c.cst_ym=?");
        assertThat(sql.getParameterMappings()).extracting("property").containsExactly("month");
        assertThat(configuration.newMetaObject(Map.of("month", "2026-10")).getValue("month")).isEqualTo("2026-10");
    }
}
