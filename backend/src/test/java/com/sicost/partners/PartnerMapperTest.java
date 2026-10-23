package com.sicost.partners;

import static org.assertj.core.api.Assertions.*;
import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class PartnerMapperTest {
    @Test void xmlBindsEveryStatementAndOptionalFiltersWithoutNullPredicates()throws Exception {
        var configuration=new Configuration();
        try(var in=new ClassPathResource("mappers/partners/PartnerMapper.xml").getInputStream()) {
            new XMLMapperBuilder(in,configuration,"mappers/partners/PartnerMapper.xml",configuration.getSqlFragments()).parse();
        }
        for(var method:PartnerMapper.class.getDeclaredMethods()) assertThat(configuration.hasStatement(PartnerMapper.class.getName()+"."+method.getName())).isTrue();
        var defaultQuery=PartnerQuery.of(Map.of(),LocalDate.of(2026,10,22));
        var defaultSql=configuration.getMappedStatement(PartnerMapper.class.getName()+".count").getBoundSql(defaultQuery);
        assertThat(defaultSql.getParameterMappings()).extracting(p->p.getProperty()).containsExactly("useYn");
        var filtered=configuration.getMappedStatement(PartnerMapper.class.getName()+".count").getBoundSql(PartnerQuery.of(Map.of("kind","VEND","q","1234567890"),defaultQuery.today()));
        assertThat(filtered.getParameterMappings()).extracting(p->p.getProperty()).containsExactly("kind","useYn","q","q");
        assertThat(filtered.getSql()).contains("IN (?,'BOTH')","replace(p.biz_reg_no,'-','')");
        var listSql=configuration.getMappedStatement(PartnerMapper.class.getName()+".list").getBoundSql(defaultQuery);
        assertThat(listSql.getSql()).contains("count(DISTINCT a.dev_id)","right(p.acnt_no,4)","NULLS LAST, ptnr_id ASC");
    }
}
