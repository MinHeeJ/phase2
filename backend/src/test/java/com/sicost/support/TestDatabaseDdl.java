package com.sicost.support;

import java.sql.SQLException;
import java.sql.Statement;

/** PostgreSQL database identifiers cannot be bind parameters. Restrict DDL to disposable test DBs. */
public final class TestDatabaseDdl {
    private TestDatabaseDdl() {}

    private static String identifier(Statement statement, String database) throws SQLException {
        if (database == null || !database.matches("sicost_test_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid isolated test database identifier");
        }
        return statement.enquoteIdentifier(database, true);
    }

    public static void create(Statement statement, String database) throws SQLException {
        statement.execute("CREATE DATABASE %s TEMPLATE template0".formatted(identifier(statement, database)));
    }

    public static void drop(Statement statement, String database) throws SQLException {
        statement.execute("DROP DATABASE %s WITH (FORCE)".formatted(identifier(statement, database)));
    }
}
