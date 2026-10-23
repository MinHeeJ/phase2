package com.sicost.admin;

import static org.assertj.core.api.Assertions.*;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

class AdminMapperTest {
    @Test void mapperXmlParsesAndAllMethodsHaveStatements()throws Exception {
        Configuration configuration=new Configuration();
        try(var in=new ClassPathResource("mappers/admin/AdminMapper.xml").getInputStream()) {
            new XMLMapperBuilder(in,configuration,"mappers/admin/AdminMapper.xml",configuration.getSqlFragments()).parse();
        }
        for(var method:AdminMapper.class.getDeclaredMethods())assertThat(configuration.hasStatement(AdminMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        var q=AdminQuery.of(new com.sicost.common.api.PageRequest(2,20,"cd_nm,desc"),"codes","JOB",null,null);
        var statement=configuration.getMappedStatement(AdminMapper.class.getName()+".codes");
        assertThat(statement.getBoundSql(q).getSql()).contains("ORDER BY cd_nm desc, sort_seq ASC, dtl_cd ASC");
        var history=configuration.getMappedStatement(AdminMapper.class.getName()+".historyCount");
        assertThat(history.getBoundSql(AdminQuery.of(new com.sicost.common.api.PageRequest(1,50,null),"history",null,null,null)).getParameterMappings()).isEmpty();
    }
}
