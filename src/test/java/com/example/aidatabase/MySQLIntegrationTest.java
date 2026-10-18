package com.example.aidatabase;

import java.sql.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

class MySQLIntegrationTest {
    @Test @EnabledIfEnvironmentVariable(named="TEST_MYSQL_URL", matches=".+")
    void createsOnlyAnIsolatedTestDatabase() throws Exception {
        String name = "aidb_test_" + UUID.randomUUID().toString().replace("-", "");
        Schema schema = Schema.parse(SchemaTest.VALID.replace("\"example\"", "\"" + name + "\""));
        try (Connection connection = DriverManager.getConnection(System.getenv("TEST_MYSQL_URL"),
                System.getenv("TEST_MYSQL_USER"), System.getenv("TEST_MYSQL_PASSWORD"))) {
            try {
                AIDatabaseBuilder.execute(connection, schema);
                try (Statement query = connection.createStatement(); ResultSet rows = query.executeQuery("SELECT COUNT(*) FROM `" + name + "`.`items`")) {
                    assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
                }
                assertThrows(SQLException.class, () -> AIDatabaseBuilder.execute(connection, schema));
            } finally {
                try (Statement cleanup = connection.createStatement()) { cleanup.executeUpdate("DROP DATABASE IF EXISTS `" + name + "`"); }
            }
        }
    }
}
