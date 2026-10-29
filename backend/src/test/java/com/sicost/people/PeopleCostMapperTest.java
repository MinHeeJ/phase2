package com.sicost.people;

import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.people.PeopleCostModels.*;

class PeopleCostMapperTest {
    static Configuration configuration() throws Exception {
        var config=new Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.setArgNameBasedConstructorAutoMapping(true);
        String path="mappers/people/PeopleCostMapper.xml";
        try(var input=new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input,config,path,config.getSqlFragments()).parse();
        }
        return config;
    }
    @Test void projectionsMatchRecordNamesTypesAndOrderWithoutDatabase() throws Exception {
        var config=configuration();
        for(var entry:Map.of("personMonth",PersonMonth.class,"assignmentInfo",AssignmentInfo.class).entrySet()) {
            var components=entry.getValue().getRecordComponents();
            var mappings=config.getResultMap(PeopleCostMapper.class.getName()+"."+entry.getKey()).getConstructorResultMappings();
            assertThat(mappings).hasSize(components.length);
            for(int i=0;i<components.length;i++) {
                assertThat(mappings.get(i).getColumn()).isEqualTo(components[i].getName());
                assertThat(mappings.get(i).getJavaType()).isEqualTo(components[i].getType());
            }
        }
    }
    @Test void queriesAreReadOnlyAndAllDisplayFilteringIsAfterAllocation() throws Exception {
        var config=configuration();
        var period=new Period("2026-01","2026-10");
        for(String method:List.of("population","assignments")) {
            var statement=config.getMappedStatement(PeopleCostMapper.class.getName()+"."+method);
            assertThat(statement.getSqlCommandType()).isEqualTo(org.apache.ibatis.mapping.SqlCommandType.SELECT);
            assertThat(statement.getBoundSql(period).getSql()).doesNotContain("IS NULL OR ?", "LIMIT", "OFFSET");
        }
    }
}
