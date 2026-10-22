package com.sicost.common;

import java.sql.Connection;
import java.sql.SQLException;

/** Identifiers cannot be bound as SQL values; permit only generated test DB names. */
public final class TestDatabaseIdentifier {
    private TestDatabaseIdentifier() {}

    public static String quoted(String name) {
        if (name == null || !name.matches("sicost_(test|admin)_[0-9a-f]{32}")) {
            throw new IllegalArgumentException("Invalid test database identifier");
        }
        return "\"" + name + "\"";
    }

    public static void create(Connection connection, String name) throws SQLException {
        String identifier = quoted(name);
        try (var statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + identifier);
        }
    }

    public static void drop(Connection connection, String name) throws SQLException {
        String identifier = quoted(name);
        try (var statement = connection.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + identifier + " WITH (FORCE)");
        }
    }
}
