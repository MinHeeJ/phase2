package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.persistence.SchemaValidator;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;

class FinanceSchemaTest {
    private String read(String resource) throws Exception {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    @Test void financeMetadataCoversIncrementalSchemaWithoutReplacingBaseline() throws Exception {
        var json = new ObjectMapper();
        var baseline = json.readValue(read("schema-metadata.json"), SchemaValidator.Table[].class);
        var finance = json.readValue(read("finance-schema-metadata.json"), SchemaValidator.Table[].class);
        assertThat(baseline).hasSize(9);
        assertThat(finance).extracting(SchemaValidator.Table::table)
            .containsExactly("fin.entry", "fin.pay", "fin.cash_bal", "fin.pay_execution");
        assertThat(Arrays.stream(finance).flatMap(t -> t.columns().stream()))
            .anySatisfy(c -> {
                assertThat(c.get("name")).isEqualTo("deleted_yn");
                assertThat(c.get("nullable")).isEqualTo(false);
                assertThat(c.get("default")).isEqualTo("'N'");
            });
        var execution = Arrays.stream(finance).filter(t -> t.table().equals("fin.pay_execution")).findFirst().orElseThrow();
        assertThat(execution.primaryKey()).containsExactly("pay_execution_id");
        assertThat(execution.foreignKeys()).extracting(SchemaValidator.ForeignKey::parent)
            .containsExactlyInAnyOrder("fin.pay", "fin.pay_execution");
        assertThat(execution.columns()).extracting(c -> c.get("name"))
            .containsExactly("pay_execution_id", "pay_id", "pay_dt", "pay_amt", "reversal_of_id", "rsn_desc", "created_at", "created_by");
    }
    @Test void migrationKeepsCopyHistoryAndLimitsSourceAndCarryUniqueness() throws Exception {
        String migration = read("db/migration/V6__finance_payment_integrity.sql");
        assertThat(migration).contains("deleted_yn CHAR(1) NOT NULL DEFAULT 'N'",
            "ON fin.entry (entry_ym, copy_entry_id)", "WHERE copy_entry_id IS NOT NULL",
            "ON fin.pay (pay_src_cd, src_rec_id, pay_se_cd, pay_ym)",
            "cry_pay_id IS NULL", "src_rec_id IS NOT NULL", "pay_src_cd <> 'MANUAL'",
            "ON fin.pay (cry_pay_id)", "pay_stts_cd <> 'CANCEL'", "ON fin.cash_bal (init_yn)",
            "WHERE init_yn = 'Y'", "RAISE EXCEPTION", "GROUP BY entry_ym, copy_entry_id");
        assertThat(migration).doesNotContain("DELETE FROM fin.entry", "DROP CONSTRAINT fk_entry_copy_entry");
    }
    @Test void migrationBackfillsStoredActualsAndMakesExecutionsAppendOnly() throws Exception {
        String migration = read("db/migration/V6__finance_payment_integrity.sql");
        assertThat(migration).contains("CREATE TABLE IF NOT EXISTS fin.pay_execution",
            "REFERENCES fin.pay (pay_id)", "REFERENCES fin.pay_execution (pay_execution_id)",
            "SELECT pay_id, pay_dt, pay_amt, created_at, created_by FROM fin.pay",
            "pay_stts_cd = 'PAID' AND pay_dt IS NOT NULL", "WHERE reversal_of_id IS NOT NULL",
            "BEFORE UPDATE OR DELETE ON fin.pay_execution");
    }
    @Test void migrationDoesNotWriteCloseSnapshotsSourceDomainsOrCalculateTaxes() throws Exception {
        String migration = read("db/migration/V6__finance_payment_integrity.sql");
        assertThat(migration).doesNotContain("INSERT INTO fin.mth_cls", "UPDATE fin.mth_cls",
            "INSERT INTO fin.mth_pl", "UPDATE fin.mth_pl", "INSERT INTO fin.corp_tax_mth",
            "UPDATE fin.corp_tax_mth", "INSERT INTO cst.", "UPDATE cst.", "INSERT INTO bill.", "UPDATE bill.");
    }
    @Test void approvedOperationsKeepExcludedProcessingOutsideFinanceHttpContract() throws Exception {
        Map<String, Object> contract;
        try (var input = new ClassPathResource("contracts/openapi.yaml").getInputStream()) {
            contract = new org.yaml.snakeyaml.Yaml().load(input);
        }
        Map<?, ?> paths = (Map<?, ?>) contract.get("paths");
        var finance = paths.keySet().stream().map(Object::toString).filter(p -> p.startsWith("/api/finance/")).toList();
        assertThat(paths.containsKey("/api/month-close")).isTrue();
        assertThat(finance).noneMatch(p -> List.of("/close", "/snapshot", "/tax/calculate", "/vat", "/bank/transfer", "/ai/")
            .stream().anyMatch(p::contains));
        var closeMethods = ((Map<?, ?>) paths.get("/api/month-close")).keySet().stream().map(Object::toString).toList();
        assertThat(closeMethods).contains("get").doesNotContain("post", "put", "delete");
    }
}
