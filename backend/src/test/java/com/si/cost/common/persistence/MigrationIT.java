package com.si.cost.common.persistence;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "TEST_DATABASE_URL", matches = ".+")
class MigrationIT {
    @Test void emptyPostgresHasOriginalMetadataSeedsAndStableRestartChecksums() throws Exception {
        String url = System.getenv("TEST_DATABASE_URL");
        String user = System.getenv("TEST_DB_USERNAME");
        String password = System.getenv("TEST_DB_PASSWORD");
        var flyway = Flyway.configure().dataSource(url, user, password).defaultSchema("public")
                .locations("classpath:db/migration").load();
        flyway.migrate();
        flyway.validate();
        try (Connection db = DriverManager.getConnection(url, user, password)) {
            assertThat(number(db, "SELECT count(*) FROM information_schema.schemata WHERE schema_name IN ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai')")).isEqualTo(11);
            assertThat(number(db, "SELECT count(*) FROM information_schema.tables WHERE table_schema IN ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai') AND table_type='BASE TABLE'")).isEqualTo(80);
            for (var entry : java.util.Map.of("cmn.cd_grp",36,"cmn.cd_dtl",189,"sys.role",5,"sys.menu",41,"sys.role_menu_auth",155,"fin.mth_cls",10).entrySet()) {
                assertThat(number(db, "SELECT count(*) FROM " + entry.getKey())).as(entry.getKey()).isEqualTo(entry.getValue().longValue());
            }
            assertThat(number(db, "SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='JOB'")).isEqualTo(12);
            assertThat(number(db, "SELECT count(*) FROM cmn.cd_dtl WHERE cd_grp_id='JOB' AND dtl_cd IN ('BE','OPS')")).isEqualTo(2);
            assertThat(text(db, "SELECT attr1_val FROM cmn.cd_dtl WHERE cd_grp_id='POSITION' AND dtl_cd='TL'")).isEqualTo("200000");
            assertThat(text(db, "SELECT cd_nm FROM cmn.cd_dtl WHERE cd_grp_id='CONTRACT_TYPE' AND dtl_cd='TK'")).isEqualTo("턴키");
            assertThat(number(db, "SELECT num_val FROM cmn.oprt_std WHERE oprt_std_id='VAT_RATE' AND aply_bgn_ym='2000-01'")).isEqualTo(10);
            assertThat(number(db, "SELECT count(*) FROM information_schema.views WHERE table_schema IN ('cmn','sys','mst','bsn','prj','asgn','hr','cst','bill','fin','ai')")).isEqualTo(4);
            assertThat(number(db, "SELECT count(*) FROM information_schema.key_column_usage WHERE constraint_name='pk_oprt_std'")).isEqualTo(2);
            assertThat(text(db, "SELECT data_type FROM information_schema.columns WHERE table_schema='cmn' AND table_name='excel_map' AND column_name='map_id'")).isEqualTo("bigint");
            assertThat(number(db, "SELECT numeric_scale FROM information_schema.columns WHERE table_schema='cmn' AND table_name='oprt_std' AND column_name='num_val'")).isEqualTo(4);
            assertThat(number(db, "SELECT count(*) FROM sys.menu WHERE scrn_id IS NOT NULL")).isEqualTo(31);
            assertThat(number(db, "SELECT count(*) FROM sys.menu WHERE scrn_id='UI-22'")).isZero();
            assertThat(number(db, "SELECT count(*) FROM sys.menu WHERE mbl_expsr_yn='N' AND scrn_id IN ('UI-10','UI-20','UI-14','UI-23','UI-24','UI-25','UI-27')")).isEqualTo(7);
            try (var statement = db.createStatement(); var rows = statement.executeQuery("SELECT cls_ym,cls_stts_cd,cls_at,cls_user_id,rls_user_id,rls_at FROM fin.mth_cls ORDER BY cls_ym")) {
                int month = 1;
                while (rows.next()) {
                    var ym = YearMonth.of(2026, month++);
                    assertThat(rows.getString(1)).isEqualTo(ym.toString());
                    assertThat(rows.getString(2)).isEqualTo(ym.getMonthValue() < 10 ? "CLOSED" : "OPEN");
                    assertThat(rows.getObject(3, LocalDateTime.class)).isEqualTo(ym.getMonthValue() < 10 ? ym.plusMonths(1).atDay(5).atStartOfDay() : null);
                    assertThat(rows.getObject(4)).isNull(); assertThat(rows.getObject(5)).isNull(); assertThat(rows.getObject(6)).isNull();
                }
                assertThat(month).isEqualTo(11);
            }
            var checksums = checksums(db);
            assertThat(flyway.migrate().migrationsExecuted).isZero();
            flyway.validate();
            assertThat(checksums(db)).containsExactlyElementsOf(checksums);
            assertThat(number(db, "SELECT count(*) FROM fin.mth_cls")).isEqualTo(10);
        }
    }
    private long number(Connection db, String sql) throws Exception {
        try (var s = db.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getLong(1); }
    }
    private String text(Connection db, String sql) throws Exception {
        try (var s = db.createStatement(); var r = s.executeQuery(sql)) { r.next(); return r.getString(1); }
    }
    private java.util.List<String> checksums(Connection db) throws Exception {
        var values = new ArrayList<String>();
        try (var s = db.createStatement(); var r = s.executeQuery("SELECT version, checksum FROM public.flyway_schema_history WHERE success ORDER BY installed_rank")) {
            while (r.next()) values.add(r.getString(1) + ":" + r.getString(2));
        }
        return values;
    }
}
