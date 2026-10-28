package com.sicost.receipts;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class BillingReceiptMapperTest {
    @Test void xmlLoadsTypedConstructorsAndAllReceiptStatementsWithoutDatabase() throws Exception {
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(new DriverManagerDataSource());
        factory.setMapperLocations(new ClassPathResource("mappers/receipts/BillingReceiptMapper.xml"));
        factory.afterPropertiesSet();
        var configuration = factory.getObject().getConfiguration();
        assertThat(configuration.hasMapper(BillingReceiptMapper.class)).isTrue();
        for (String name : new String[]{"bill", "lockBill", "receipt", "receipts", "insert", "update", "delete",
                "amendmentTotal", "receivedExcluding", "monthStatus", "billing", "details", "amendments", "history", "warnings", "audit"}) {
            assertThat(configuration.hasStatement("com.sicost.receipts.BillingReceiptMapper." + name)).isTrue();
        }
        var lock = configuration.getMappedStatement("com.sicost.receipts.BillingReceiptMapper.lockBill");
        assertThat(lock.isFlushCacheRequired()).isTrue();
        assertThat(lock.isUseCache()).isFalse();
        assertThat(lock.getResultMaps().get(0).getType()).isEqualTo(BillingReceiptModels.BillState.class);
        assertThat(configuration.getMappedStatement("com.sicost.receipts.BillingReceiptMapper.receipts")
            .getResultMaps().get(0).getType()).isEqualTo(BillingReceiptModels.ReceiptRow.class);
    }
}
