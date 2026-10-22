package com.sicost.common;

import java.sql.Connection;
import java.sql.Statement;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TestDatabaseIdentifierTest {
    @Test
    void onlyGeneratedNamesReachDdlAndAreQuoted() throws Exception {
        var connection = mock(Connection.class);
        var statement = mock(Statement.class);
        when(connection.createStatement()).thenReturn(statement);
        String name = "sicost_test_0123456789abcdef0123456789abcdef";
        TestDatabaseIdentifier.create(connection, name);
        TestDatabaseIdentifier.drop(connection, name);
        verify(statement).execute("CREATE DATABASE \"sicost_test_0123456789abcdef0123456789abcdef\"");
        verify(statement).execute("DROP DATABASE IF EXISTS \"sicost_test_0123456789abcdef0123456789abcdef\" WITH (FORCE)");
        assertThat(TestDatabaseIdentifier.quoted("sicost_admin_0123456789abcdef0123456789abcdef"))
                .isEqualTo("\"sicost_admin_0123456789abcdef0123456789abcdef\"");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"postgres", "sicost_test_x", "sicost_test_0123456789abcdef0123456789abcdef;DROP DATABASE postgres", "sicost_test_0123456789abcdef0123456789abcdef\"", "sicost_test_0123456789abcdef0123456789abcdef\n"})
    void untrustedNamesNeverReachConnection(String name) {
        var connection = mock(Connection.class);
        assertThatThrownBy(() -> TestDatabaseIdentifier.create(connection, name)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TestDatabaseIdentifier.drop(connection, name)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(connection);
    }
}
