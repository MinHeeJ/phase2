package com.sicost.partnersops;

import static org.assertj.core.api.Assertions.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import java.time.LocalDate;
import java.util.*;
import static com.sicost.partnersops.PartnerLifecycleModels.*;

class PartnerLifecycleMapperTest {
    @Test void xmlStatementsAndDynamicOptionalBindings()throws Exception {
        var configuration=new Configuration();String path="mappers/partnersops/PartnerLifecycleMapper.xml";
        try(var in=new ClassPathResource(path).getInputStream()){new XMLMapperBuilder(in,configuration,path,configuration.getSqlFragments()).parse();}
        for(var method:PartnerLifecycleMapper.class.getDeclaredMethods())assertThat(configuration.hasStatement(PartnerLifecycleMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        var statement=configuration.getMappedStatement(PartnerLifecycleMapper.class.getName()+".exportRows");
        var all=statement.getBoundSql(new PartnerExportQuery(null,null,null,null,LocalDate.of(2026,10,22)));
        assertThat(all.getParameterMappings()).allMatch(p->p.getProperty().equals("today"));
        var filtered=statement.getBoundSql(new PartnerExportQuery("CUST","Y","이름",List.of("1","2"),LocalDate.of(2026,10,22)));
        assertThat(filtered.getSql()).contains("p.ptnr_se_cd IN", "p.use_yn=", "position(lower(?) in lower(p.ptnr_nm))", "replace(p.biz_reg_no,'-','')=replace(?,'-','')", "p.ptnr_id IN").doesNotContain("LIMIT", "OFFSET", "IS NULL OR", "ILIKE");
    }
}
