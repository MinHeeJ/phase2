package com.sicost.entries;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static com.sicost.entries.FinanceEntryModels.*;

class FinanceEntryMapperTest {
    @Test void mapperLoadsEveryOperationAndBindsRecordProperties()throws Exception {
        var configuration=new Configuration();
        String resource="mappers/entries/FinanceEntryMapper.xml";
        try(InputStream input=getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(input).isNotNull();
            new XMLMapperBuilder(input,configuration,resource,configuration.getSqlFragments()).parse();
        }
        String prefix=FinanceEntryMapper.class.getName()+".";
        for(var method:FinanceEntryMapper.class.getDeclaredMethods())assertThat(configuration.hasStatement(prefix+method.getName())).as(method.getName()).isTrue();
        var write=new EntryWrite(42,new EntryInput("2026-10","SGA","경비",0,17,null,"N","N","비고",null,"N"),null);
        var meta=configuration.newMetaObject(write);
        for(String name:new String[]{"insert","update"}) {
            var sql=configuration.getMappedStatement(prefix+name).getBoundSql(write);
            for(var parameter:sql.getParameterMappings())assertThatCode(()->meta.getValue(parameter.getProperty())).doesNotThrowAnyException();
        }
        var map=configuration.getResultMap(prefix+"entry");
        var types=map.getConstructorResultMappings().stream().map(m->m.getJavaType()).toArray(Class<?>[]::new);
        assertThat(FinanceEntry.class.getDeclaredConstructor(types)).isNotNull();
        assertThat(configuration.getMappedStatement(prefix+"insert").isDirtySelect()).isTrue();
    }
}
