package com.sicost.shared;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class SharedReadMapperContractTest {
    @Test void xmlLoadsAllTypedStatementsWithoutDatabase() throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(new DriverManagerDataSource());
        factory.setMapperLocations(new ClassPathResource("mappers/shared/SharedReadMapper.xml"));
        var configuration = factory.getObject().getConfiguration();
        for (String statement : new String[] {"groupExists", "codeOptions", "menus", "monthClose", "effectiveParam"}) {
            assertThat(configuration.hasStatement("com.sicost.shared.SharedReadMapper." + statement)).isTrue();
        }
        var bound = configuration.getMappedStatement("com.sicost.shared.SharedReadMapper.effectiveParam")
            .getBoundSql(java.util.Map.of("key", "VAT_RATE", "month", "2026-10"));
        assertThat(bound.getParameterMappings()).extracting(mapping -> mapping.getProperty()).containsExactly("key", "month");
        assertThat(configuration.getResultMap("com.sicost.shared.SharedReadMapper.operatingParam").getType())
            .isEqualTo(SharedReadModels.OperatingParam.class);
    }
}
