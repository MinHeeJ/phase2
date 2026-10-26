package com.sicost.support;

import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestDatabaseDdlTest {
    @Test void createsAndDropsOnlyQuotedIsolatedDatabase() throws Exception {
        var statement = mock(Statement.class);
        String database = "sicost_test_0123456789abcdef0123456789abcdef";
        when(statement.enquoteIdentifier(database, true)).thenReturn("\"" + database + "\"");
        TestDatabaseDdl.create(statement, database);
        TestDatabaseDdl.drop(statement, database);
        verify(statement).execute("CREATE DATABASE \"sicost_test_0123456789abcdef0123456789abcdef\" TEMPLATE template0");
        verify(statement).execute("DROP DATABASE \"sicost_test_0123456789abcdef0123456789abcdef\" WITH (FORCE)");
        verify(statement, times(2)).enquoteIdentifier(database, true);
        verifyNoMoreInteractions(statement);
    }

    @ParameterizedTest @NullAndEmptySource
    @ValueSource(strings = {"postgres", "sicost_test_short", "sicost_test_0123456789abcdef0123456789abcdef;DROP DATABASE postgres", "sicost_test_0123456789abcdef0123456789abcde\"", "sicost_test_0123456789abcdef0123456789abcdef\n",
        " sicost_test_0123456789abcdef0123456789abcdef", "sicost_test_0123456789abcdef0123456789abcdef--",
        "sicost_test_0123456789ABCDEF0123456789ABCDEF", "sicost_test_0123456789abcdef0123456789abcdef0"})
    void rejectsInvalidIdentifiersBeforeAnyJdbcCall(String database) throws Exception {
        var statement = mock(Statement.class);
        assertThatThrownBy(() -> TestDatabaseDdl.create(statement, database)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestDatabaseDdl.drop(statement, database)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(statement);
    }

    @Test void quotingFailureNeverExecutesDdl() throws Exception {
        var statement = mock(Statement.class);
        String database = "sicost_test_0123456789abcdef0123456789abcdef";
        when(statement.enquoteIdentifier(database, true)).thenThrow(new java.sql.SQLException("Identifier quoting unavailable"));
        assertThatThrownBy(() -> TestDatabaseDdl.create(statement, database)).isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> TestDatabaseDdl.drop(statement, database)).isInstanceOf(java.sql.SQLException.class);
        verify(statement, times(2)).enquoteIdentifier(database, true);
        verifyNoMoreInteractions(statement);
    }
}
