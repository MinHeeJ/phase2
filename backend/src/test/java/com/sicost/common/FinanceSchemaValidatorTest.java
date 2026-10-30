package com.sicost.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sicost.common.persistence.SchemaValidator;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FinanceSchemaValidatorTest {
    final ObjectMapper json = new ObjectMapper();
    final DataSource source = mock(DataSource.class);
    final DatabaseMetaData metadata = mock(DatabaseMetaData.class);
    final Map<String, SchemaValidator.Table> tables = new HashMap<>();

    @BeforeEach void setup() throws Exception {
        var connection = mock(Connection.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(metadata.getDatabaseProductName()).thenReturn("PostgreSQL");
        for (String resource : List.of("schema-metadata.json", "finance-schema-metadata.json")) {
            try (var input = new ClassPathResource(resource).getInputStream()) {
                for (var table : json.readValue(input, SchemaValidator.Table[].class)) tables.put(table.table(), table);
            }
        }
        when(metadata.getColumns(isNull(), anyString(), anyString(), isNull())).thenAnswer(i -> {
            var table = tables.get(i.getArgument(1) + "." + i.getArgument(2));
            var rows = new ArrayList<Map<String, Object>>();
            for (var column : table.columns()) {
                var row = new HashMap<String, Object>();
                String type = column.get("type").toString();
                row.put("COLUMN_NAME", column.get("name"));
                row.put("TYPE_NAME", type.replaceFirst("\\(.*", "").toLowerCase(java.util.Locale.ROOT));
                if (type.startsWith("CHAR(")) row.put("TYPE_NAME", "bpchar");
                if (type.contains("(")) {
                    String[] dimensions = type.substring(type.indexOf('(') + 1, type.length() - 1).split(",");
                    row.put("COLUMN_SIZE", Integer.parseInt(dimensions[0]));
                    if (dimensions.length > 1) row.put("DECIMAL_DIGITS", Integer.parseInt(dimensions[1]));
                    if (dimensions.length > 1) row.put("TYPE_NAME", "numeric");
                }
                row.put("NULLABLE", (Boolean) column.get("nullable") ? DatabaseMetaData.columnNullable : DatabaseMetaData.columnNoNulls);
                row.put("IS_AUTOINCREMENT", (Boolean) column.get("identity") ? "YES" : "NO");
                row.put("COLUMN_DEF", column.get("default"));
                rows.add(row);
            }
            return result(rows);
        });
        when(metadata.getPrimaryKeys(isNull(), anyString(), anyString())).thenAnswer(i -> {
            var table = tables.get(i.getArgument(1) + "." + i.getArgument(2));
            var rows = new ArrayList<Map<String, Object>>();
            for (int n = 0; n < table.primaryKey().size(); n++)
                rows.add(Map.of("KEY_SEQ", n + 1, "COLUMN_NAME", table.primaryKey().get(n)));
            return result(rows);
        });
        when(metadata.getImportedKeys(isNull(), anyString(), anyString())).thenAnswer(i -> {
            var table = tables.get(i.getArgument(1) + "." + i.getArgument(2));
            var rows = new ArrayList<Map<String, Object>>();
            for (var key : table.foreignKeys()) {
                var parent = key.parent().split("\\.");
                for (int n = 0; n < key.columns().size(); n++) rows.add(Map.of("FK_NAME", key.name(), "KEY_SEQ", n + 1,
                    "FKCOLUMN_NAME", key.columns().get(n), "PKTABLE_SCHEM", parent[0], "PKTABLE_NAME", parent[1], "PKCOLUMN_NAME", key.targets().get(n)));
            }
            return result(rows);
        });
        when(metadata.getIndexInfo(isNull(), anyString(), anyString(), eq(true), eq(false))).thenAnswer(i -> {
            var table = tables.get(i.getArgument(1) + "." + i.getArgument(2));
            var rows = new ArrayList<Map<String, Object>>();
            for (int k = 0; k < table.uniqueKeys().size(); k++) {
                var key = table.uniqueKeys().get(k);
                for (int n = 0; n < key.size(); n++) rows.add(Map.of("INDEX_NAME", "unique_" + k,
                    "ORDINAL_POSITION", n + 1, "COLUMN_NAME", key.get(n)));
            }
            return result(rows);
        });
    }
    ResultSet result(List<Map<String, Object>> rows) throws Exception {
        var result = mock(ResultSet.class);
        var index = new AtomicInteger(-1);
        when(result.next()).thenAnswer(i -> index.incrementAndGet() < rows.size());
        when(result.getString(anyString())).thenAnswer(i -> {
            var value = rows.get(index.get()).get(i.getArgument(0));
            return value == null ? null : value.toString();
        });
        when(result.getInt(anyString())).thenAnswer(i -> ((Number) rows.get(index.get()).getOrDefault(i.getArgument(0), 0)).intValue());
        when(result.getShort(anyString())).thenAnswer(i -> ((Number) rows.get(index.get()).getOrDefault(i.getArgument(0), 0)).shortValue());
        return result;
    }
    @Test void startupValidatesBothBaselineAndFinanceInventories() throws Exception {
        new SchemaValidator(source, json).afterPropertiesSet();
        verify(metadata).getColumns(null, "cmn", "oprt_std", null);
        verify(metadata).getColumns(null, "fin", "entry", null);
        verify(metadata).getColumns(null, "fin", "pay_execution", null);
        verify(metadata).getImportedKeys(null, "fin", "pay_execution");
    }
    @Test void missingFinanceExecutionTableFailsStartup() throws Exception {
        var empty = result(List.of());
        when(metadata.getColumns(null, "fin", "pay_execution", null)).thenReturn(empty);
        assertThatThrownBy(() -> new SchemaValidator(source, json).validate())
            .hasMessageContaining("fin.pay_execution column inventory");
    }
    @Test void missingFinanceForeignKeyFailsStartup() throws Exception {
        var empty = result(List.of());
        when(metadata.getImportedKeys(null, "fin", "pay_execution")).thenReturn(empty);
        assertThatThrownBy(() -> new SchemaValidator(source, json).validate())
            .hasMessageContaining("fin.pay_execution foreign key inventory");
    }
}
