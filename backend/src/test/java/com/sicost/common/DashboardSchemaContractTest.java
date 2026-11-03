package com.sicost.common;

import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import static org.assertj.core.api.Assertions.*;

class DashboardSchemaContractTest {
    @ParameterizedTest
    @CsvSource({
        "V1__schema.sql,0540263f708842f2c0035c2d5e3a3770a0770045da145cee3ad088d0210c0de1",
        "V2__seed_month_close.sql,520c244a69beb3eb95978f30046095ea859f31bc96c860c027be6ba6b2acfdbb",
        "V3__seed_bank.sql,4773a2ee688d7746bdba0ecc89c4a4b016f63cdcf2b0243459771347ab7b17cf",
        "V4__asgn_chg_hist_ext.sql,7e942bde18d4463e9f1b0271c940f365664889933f8ef9fc40f105b28098b256",
        "V5__seed_plan_gap.sql,3b0bb822dea9244a7e3da74dc57ec29567e8805f32cadb6fcce72d50c231d48b",
        "V6__finance_payment_integrity.sql,68eca0b8c918cf9fd056388941970cb3414e34faad659e61012ae3650ef637dd",
        "V7__monthly_closing_integrity.sql,431d0c23b86fa040743c676123567bf7cdb6e51d48151dd0b50821f4e655ddda",
        "V8__workforce_planning_integrity.sql,b1020d694eadb682ed9c58badac53765f19c7ce6922624cf97ce4bf6362cafc2"
    })
    void existingMigrationBytesAreImmutable(String name,String expected) throws Exception {
        try (var input=new ClassPathResource("db/migration/"+name).getInputStream()) {
            assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input.readAllBytes())))
                .as(name).isEqualTo(expected);
        }
    }

    @Test void dashboardIntroducesNoMigrationOrProductSeed() throws Exception {
        var resources=new PathMatchingResourcePatternResolver().getResources("classpath:db/migration/*.sql");
        assertThat(Arrays.stream(resources).map(r->r.getFilename())).containsExactlyInAnyOrder(
            "V1__schema.sql","V2__seed_month_close.sql","V3__seed_bank.sql","V4__asgn_chg_hist_ext.sql",
            "V5__seed_plan_gap.sql","V6__finance_payment_integrity.sql","V7__monthly_closing_integrity.sql",
            "V8__workforce_planning_integrity.sql");
    }
}
