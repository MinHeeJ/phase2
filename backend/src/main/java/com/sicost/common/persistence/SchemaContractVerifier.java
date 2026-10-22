package com.sicost.common.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
public class SchemaContractVerifier implements ApplicationRunner {
    private final DataSource dataSource;
    private final ObjectMapper objectMapper;

    public SchemaContractVerifier(DataSource dataSource, ObjectMapper objectMapper) {
        this.dataSource = dataSource;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(ApplicationArguments args) throws SQLException, IOException {
        verify();
    }

    public void verify() throws SQLException, IOException {
        JsonNode contracts;
        try (var input = new ClassPathResource("db/schema-contract.json").getInputStream()) {
            contracts = objectMapper.readTree(input);
        }
        try (Connection connection = dataSource.getConnection()) {
            if (!connection.getMetaData().getDatabaseProductName().equals("PostgreSQL")) {
                throw new IllegalStateException("PostgreSQL이 필요합니다.");
            }
            for (JsonNode contract : contracts) verifyTable(connection, contract);
        }
    }

    private void verifyTable(Connection connection, JsonNode contract) throws SQLException {
        String table = contract.path("table").asText();
        String[] qualified = table.split("\\.");
        String schema = qualified[0], name = qualified[1];
        for (JsonNode column : contract.path("columns")) {
            try (var statement = connection.prepareStatement("SELECT data_type, character_maximum_length, numeric_precision, numeric_scale, is_nullable, identity_generation, column_default FROM information_schema.columns WHERE table_schema = ? AND table_name = ? AND column_name = ?")) {
                statement.setString(1, schema);
                statement.setString(2, name);
                statement.setString(3, column.path("name").asText());
                try (ResultSet result = statement.executeQuery()) {
                    require(result.next(), table, column.path("name").asText());
                    require(column.path("type").asText().equals(result.getString("data_type")), table, "type");
                    require(column.path("nullable").asBoolean() == "YES".equals(result.getString("is_nullable")), table, "nullable");
                    for (String attribute : List.of("length", "precision", "scale")) {
                        String dbAttribute = Map.of("length", "character_maximum_length", "precision", "numeric_precision", "scale", "numeric_scale").get(attribute);
                        if (!column.path(attribute).isNull()) require(column.path(attribute).asInt() == result.getInt(dbAttribute), table, attribute);
                    }
                    if (!column.path("identity").asText().isEmpty()) require(column.path("identity").asText().equals(result.getString("identity_generation")), table, "identity");
                    if (column.path("name").asText().equals("created_at")) require(result.getString("column_default").contains("CURRENT_TIMESTAMP"), table, "created_at default");
                }
            }
        }
        DatabaseMetaData metadata = connection.getMetaData();
        Map<Integer, String> primary = new TreeMap<>();
        try (ResultSet keys = metadata.getPrimaryKeys(null, schema, name)) {
            while (keys.next()) primary.put(keys.getInt("KEY_SEQ"), keys.getString("COLUMN_NAME"));
        }
        require(new ArrayList<>(primary.values()).equals(strings(contract.path("primaryKey"))), table, "primary key");
        Map<String, List<ForeignColumn>> foreign = new TreeMap<>();
        try (ResultSet keys = metadata.getImportedKeys(null, schema, name)) {
            while (keys.next()) foreign.computeIfAbsent(keys.getString("FK_NAME"), ignored -> new ArrayList<>()).add(
                    new ForeignColumn(keys.getInt("KEY_SEQ"), keys.getString("FKCOLUMN_NAME"),
                            keys.getString("PKTABLE_SCHEM") + "." + keys.getString("PKTABLE_NAME"), keys.getString("PKCOLUMN_NAME")));
        }
        for (JsonNode key : contract.path("foreignKeys")) {
            List<ForeignColumn> columns = foreign.get(key.path("name").asText());
            require(columns != null, table, "foreign key");
            columns.sort(Comparator.comparingInt(ForeignColumn::position));
            require(columns.stream().map(ForeignColumn::column).toList().equals(strings(key.path("columns"))), table, "foreign columns");
            require(columns.stream().map(ForeignColumn::parentColumn).toList().equals(strings(key.path("parentColumns"))), table, "foreign parent columns");
            require(columns.stream().allMatch(c -> c.parent().equals(key.path("parent").asText())), table, "foreign parent");
        }
    }

    private static List<String> strings(JsonNode values) {
        List<String> strings = new ArrayList<>();
        values.forEach(value -> strings.add(value.asText()));
        return strings;
    }

    private static void require(boolean valid, String table, String detail) {
        if (!valid) throw new IllegalStateException("Schema contract mismatch: " + table + " " + detail);
    }

    private record ForeignColumn(int position, String column, String parent, String parentColumn) {}
}
