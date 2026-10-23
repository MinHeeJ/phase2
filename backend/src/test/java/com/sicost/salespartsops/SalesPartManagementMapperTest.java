package com.sicost.salespartsops;

import static org.assertj.core.api.Assertions.*;
import static com.sicost.salespartsops.SalesPartModels.*;
import java.time.LocalDate;
import java.util.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class SalesPartManagementMapperTest {
    Configuration configuration()throws Exception {
        Configuration c=new Configuration();
        try(var in=new ClassPathResource("mappers/salespartsops/SalesPartManagementMapper.xml").getInputStream()) {
            new XMLMapperBuilder(in,c,"mappers/salespartsops/SalesPartManagementMapper.xml",c.getSqlFragments()).parse();
        }
        return c;
    }
    @Test void mapperXmlParsesAndAllMethodsHaveStatements()throws Exception {
        var c=configuration();for(var method:SalesPartManagementMapper.class.getDeclaredMethods()) assertThat(c.hasStatement(SalesPartManagementMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
    }
    @Test void exportOptionalIdsOnlyBindWhenProvidedAndSortIsAllowListed()throws Exception {
        var c=configuration();var statement=c.getMappedStatement(SalesPartManagementMapper.class.getName()+".exportRows");
        var all=statement.getBoundSql(new ExportQuery(LocalDate.of(2026,10,22),"2026-10",null,SalesPartExcelService.order(null)));
        assertThat(all.getSql()).doesNotContain("LIMIT ? OFFSET", "p.bsn_part_id IN");assertThat(all.getParameterMappings()).noneMatch(p->p.getProperty().equals("ids"));
        var selected=statement.getBoundSql(new ExportQuery(LocalDate.of(2026,10,22),"2026-10",List.of(2L,43L),SalesPartExcelService.order("peopleCount,desc")));
        assertThat(selected.getSql()).contains("p.bsn_part_id IN", "ORDER BY \"peopleCount\" DESC,bsn_part_id ASC");
    }
    @Test void constructorMappingsMatchRecordTypesAndReturningInsertFlushesCache()throws Exception {
        var c=configuration();String prefix=SalesPartManagementMapper.class.getName()+".";
        for(String name:List.of("partRow","standardRow","optionRow","exportRow")) {
            var mapping=c.getResultMap(prefix+name);var fields=mapping.getConstructorResultMappings();var components=mapping.getType().getRecordComponents();
            assertThat(fields).hasSize(components.length);
            for(int i=0;i<components.length;i++) assertThat(fields.get(i).getJavaType()).isEqualTo(components[i].getType());
        }
        assertThat(c.getMappedStatement(prefix+"insertStandard").isFlushCacheRequired()).isTrue();
    }
}
