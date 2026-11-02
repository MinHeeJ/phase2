package com.sicost.cashflow;

import java.io.InputStream;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CashFlowMapperTest {
    @Test void xmlRegistersEveryMapperStatementAndRecordConstructor() throws Exception {
        var config=new Configuration();
        try(InputStream stream=getClass().getResourceAsStream("/mappers/cashflow/CashFlowMapper.xml")){
            assertThat(stream).isNotNull();
            new XMLMapperBuilder(stream,config,"mappers/cashflow/CashFlowMapper.xml",config.getSqlFragments()).parse();
        }
        for(var method:CashFlowMapper.class.getDeclaredMethods())assertThat(config.hasStatement(CashFlowMapper.class.getName()+"."+method.getName())).as(method.getName()).isTrue();
        assertThat(config.getResultMap("com.sicost.cashflow.CashFlowMapper.inflow").getConstructorResultMappings()).hasSize(8);
        assertThat(config.getMappedStatement("com.sicost.cashflow.CashFlowMapper.events").getBoundSql(null).getParameterMappings()).isEmpty();
    }
}
