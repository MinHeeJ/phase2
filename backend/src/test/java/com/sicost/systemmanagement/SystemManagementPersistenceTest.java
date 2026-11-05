package com.sicost.systemmanagement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sicost.common.security.AuthenticationProofs;
import com.sicost.support.TestDatabaseDdl;
import java.sql.DriverManager;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

class SystemManagementPersistenceTest {
    private String adminUrl, user, password, database;
    private DriverManagerDataSource source;
    private SystemManagementMapper mapper;

    @BeforeEach void restoreIsolatedDatabase() throws Exception {
        adminUrl = System.getenv("TEST_DATABASE_URL");
        assumeTrue(adminUrl != null && !adminUrl.isBlank(), "Real PostgreSQL integration requires TEST_DATABASE_URL");
        user = System.getenv("TEST_DATABASE_USER");
        password = System.getenv("TEST_DATABASE_PASSWORD");
        assumeTrue(user != null && password != null, "Test database connection settings are absent");
        database = "sicost_test_" + UUID.randomUUID().toString().replace("-", "");
        try (var connection = DriverManager.getConnection(adminUrl, user, password); var statement = connection.createStatement()) {
            TestDatabaseDdl.create(statement, database);
        }
        source = new DriverManagerDataSource(adminUrl.substring(0, adminUrl.lastIndexOf('/') + 1) + database, user, password);
        Flyway.configure().dataSource(source).defaultSchema("public").locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setMapperLocations(new ClassPathResource("mappers/systemmanagement/SystemManagementMapper.xml"));
        factory.afterPropertiesSet();
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(SystemManagementMapper.class);
    }

    @AfterEach void removeIsolatedDatabase() throws Exception {
        if (source == null) return;
        try (var connection = DriverManager.getConnection(adminUrl, user, password); var statement = connection.createStatement()) {
            TestDatabaseDdl.drop(statement, database);
        }
    }

    private long insert(String sql, Object... values) throws Exception {
        try (var connection = source.getConnection(); var statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            try (var rows = statement.executeQuery()) {
                assertThat(rows.next()).isTrue();
                return rows.getLong(1);
            }
        }
    }

    @Test void filtersRowsTotalsPaginationAndUnpagedExportUsingRealPostgres() throws Exception {
        String key = UUID.randomUUID().toString().replace("-", "");
        long partA = insert("INSERT INTO mst.bsn_part(bsn_part_nm) VALUES(?) RETURNING bsn_part_id", "A_" + key);
        long partB = insert("INSERT INTO mst.bsn_part(bsn_part_nm) VALUES(?) RETURNING bsn_part_id", "B_" + key);
        long emptyPart = insert("INSERT INTO mst.bsn_part(bsn_part_nm) VALUES(?) RETURNING bsn_part_id", "C_" + key);
        long roleA = insert("INSERT INTO sys.role(role_nm) VALUES(?) RETURNING role_id", "A_" + key);
        long roleB = insert("INSERT INTO sys.role(role_nm) VALUES(?) RETURNING role_id", "B_" + key);
        String hash = new BCryptPasswordEncoder(4).encode(AuthenticationProofs.randomSessionId());
        long first = insert("INSERT INTO sys.user_info(lgn_id,user_nm,pswd_hash) VALUES(?,?,?) RETURNING user_id", "a_" + key, "검증 사용자 A", hash);
        long second = insert("INSERT INTO sys.user_info(lgn_id,user_nm,pswd_hash) VALUES(?,?,?) RETURNING user_id", "b_" + key, "검증 사용자 B", hash);
        long third = insert("INSERT INTO sys.user_info(lgn_id,user_nm,pswd_hash) VALUES(?,?,?) RETURNING user_id", "c_" + key, "검증 사용자 C", hash);
        mapper.assignParts(Map.of("id", first, "ids", List.of(partA)));
        mapper.assignParts(Map.of("id", second, "ids", List.of(partA)));
        mapper.assignParts(Map.of("id", third, "ids", List.of(partB)));
        mapper.assignRoles(Map.of("id", first, "ids", List.of(roleA), "actor", first));
        mapper.assignRoles(Map.of("id", second, "ids", List.of(roleB), "actor", first));
        mapper.assignRoles(Map.of("id", third, "ids", List.of(roleA), "actor", first));

        Map<String, Object> query = new HashMap<>(Map.of("loginId", key));
        assertThat(mapper.userCount(query)).isEqualTo(3);
        query.put("partId", partA);
        assertThat(mapper.userCount(query)).isEqualTo(2);
        query.put("roleId", roleA);
        assertThat(mapper.users(query)).extracting(row -> ((Number) row.get("id")).longValue()).containsExactly(first);
        assertThat(mapper.userCount(query)).isEqualTo(1);
        assertThat(mapper.users(query).get(0)).containsEntry("loginId", "a_" + key).containsEntry("name", "검증 사용자 A");
        assertThat(mapper.users(query).get(0)).doesNotContainKeys("pswd_hash", "passwordHash");

        query.remove("partId");
        query.put("limit", 1);
        query.put("offset", 0L);
        assertThat(mapper.users(query)).hasSize(1);
        assertThat(mapper.userCount(query)).isEqualTo(2);
        query.remove("limit");
        query.remove("offset");
        assertThat(mapper.users(query)).extracting(row -> ((Number) row.get("id")).longValue()).containsExactly(third, first);
        query.put("partId", emptyPart);
        assertThat(mapper.users(query)).isEmpty();
        assertThat(mapper.userCount(query)).isZero();
        query.remove("partId");
        query.remove("roleId");
        assertThat(mapper.users(query)).hasSize(3);
    }
}
