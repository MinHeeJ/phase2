package com.sicost.common.persistence;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

@Component
@DependsOn("flywayInitializer")
public class SchemaValidator implements InitializingBean {
    public record Column(String name, String type, boolean nullable, boolean identity, String defaultValue) {}
    public record ForeignKey(String name, List<String> columns, String parent, List<String> targets) {}
    public record Table(String table, List<Map<String, Object>> columns, List<String> primaryKey,
                        List<ForeignKey> foreignKeys, List<List<String>> uniqueKeys) {}
    private final DataSource dataSource;
    private final ObjectMapper mapper;

    public SchemaValidator(DataSource dataSource, ObjectMapper mapper) {
        this.dataSource = dataSource;
        this.mapper = mapper;
    }
    @Override
    public void afterPropertiesSet() throws SQLException, IOException { validate(); }

    public void validate() throws SQLException, IOException {
        List<Table> expected = new ArrayList<>();
        for (String resource : List.of("schema-metadata.json", "finance-schema-metadata.json")) {
            try (var input = new ClassPathResource(resource).getInputStream()) {
                expected.addAll(mapper.readValue(input, new TypeReference<List<Table>>() {}));
            }
        }
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            if (!"PostgreSQL".equals(metadata.getDatabaseProductName())) fail("PostgreSQL required");
            for (Table table : expected) validateTable(metadata, table);
        }
    }
    private void validateTable(DatabaseMetaData metadata, Table table) throws SQLException {
        String[] name = table.table().split("\\.");
        Map<String, Column> actual = new HashMap<>();
        try (ResultSet rows = metadata.getColumns(null, name[0], name[1], null)) {
            while (rows.next()) {
                String type = switch (rows.getString("TYPE_NAME")) {
                    case "int8" -> "BIGINT";
                    case "int4" -> "INT";
                    case "int2" -> "SMALLINT";
                    case "varchar" -> "VARCHAR(" + rows.getInt("COLUMN_SIZE") + ")";
                    case "bpchar" -> "CHAR(" + rows.getInt("COLUMN_SIZE") + ")";
                    case "numeric" -> "DECIMAL(" + rows.getInt("COLUMN_SIZE") + "," + rows.getInt("DECIMAL_DIGITS") + ")";
                    case "timestamp" -> "TIMESTAMP";
                    case "date" -> "DATE";
                    default -> rows.getString("TYPE_NAME").toUpperCase(java.util.Locale.ROOT);
                };
                actual.put(rows.getString("COLUMN_NAME"), new Column(rows.getString("COLUMN_NAME"), type,
                    rows.getInt("NULLABLE") == DatabaseMetaData.columnNullable,
                    "YES".equals(rows.getString("IS_AUTOINCREMENT")), rows.getString("COLUMN_DEF")));
            }
        }
        if (actual.size() != table.columns().size()) fail(table.table() + " column inventory");
        for (Map<String, Object> expected : table.columns()) {
            String columnName = (String) expected.get("name");
            Column column = actual.get(columnName);
            if (column == null || !column.type().equals(expected.get("type"))
                    || column.nullable() != (Boolean) expected.get("nullable")
                    || column.identity() != (Boolean) expected.get("identity")) {
                fail(table.table() + "." + columnName + " type/nullability/identity");
            }
            String defaultValue = (String) expected.get("default");
            if (defaultValue != null && !normalizeDefault(defaultValue).equals(normalizeDefault(column.defaultValue()))) {
                fail(table.table() + "." + columnName + " default");
            }
        }
        Map<Short, String> primary = new TreeMap<>();
        try (ResultSet rows = metadata.getPrimaryKeys(null, name[0], name[1])) {
            while (rows.next()) primary.put(rows.getShort("KEY_SEQ"), rows.getString("COLUMN_NAME"));
        }
        if (!new ArrayList<>(primary.values()).equals(table.primaryKey())) fail(table.table() + " primary key");
        Map<String, Map<Short, String>> foreign = new HashMap<>();
        try (ResultSet rows = metadata.getImportedKeys(null, name[0], name[1])) {
            while (rows.next()) {
                String entry = rows.getString("FKCOLUMN_NAME") + ":" + rows.getString("PKTABLE_SCHEM")
                    + "." + rows.getString("PKTABLE_NAME") + "." + rows.getString("PKCOLUMN_NAME");
                foreign.computeIfAbsent(rows.getString("FK_NAME"), ignored -> new TreeMap<>()).put(rows.getShort("KEY_SEQ"), entry);
            }
        }
        if (foreign.size() != table.foreignKeys().size()) fail(table.table() + " foreign key inventory");
        for (ForeignKey key : table.foreignKeys()) {
            List<String> entries = new ArrayList<>();
            for (int i = 0; i < key.columns().size(); i++) entries.add(key.columns().get(i) + ":" + key.parent() + "." + key.targets().get(i));
            if (!entries.equals(new ArrayList<>(foreign.getOrDefault(key.name(), Map.of()).values()))) fail(table.table() + " " + key.name());
        }
        Map<String, Map<Short, String>> unique = new HashMap<>();
        try (ResultSet rows = metadata.getIndexInfo(null, name[0], name[1], true, false)) {
            while (rows.next()) {
                if (rows.getString("COLUMN_NAME") != null) unique.computeIfAbsent(rows.getString("INDEX_NAME"), ignored -> new TreeMap<>())
                    .put(rows.getShort("ORDINAL_POSITION"), rows.getString("COLUMN_NAME"));
            }
        }
        Set<List<String>> keys = new HashSet<>();
        unique.values().forEach(v -> keys.add(new ArrayList<>(v.values())));
        if (!keys.containsAll(table.uniqueKeys())) fail(table.table() + " unique keys");
    }
    private static String normalizeDefault(String value) {
        if (value == null) return "";
        return value.replaceAll("::[a-zA-Z ]+(\\([0-9,]+\\))?", "").replace("(", "").replace(")", "").toUpperCase(java.util.Locale.ROOT);
    }
    private static void fail(String detail) { throw new IllegalStateException("PostgreSQL schema mismatch: " + detail); }
}
