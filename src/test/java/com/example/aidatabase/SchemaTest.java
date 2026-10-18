package com.example.aidatabase;

import com.google.gson.*;
import java.io.*;
import java.lang.reflect.Proxy;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class SchemaTest {
    static final String VALID = "{\"databaseName\":\"example\",\"tables\":[{\"tableName\":\"items\",\"columns\":[{\"columnName\":\"id\",\"type\":\"INT\"}]}]}";

    @Test void generatesQuotedSQL() {
        assertEquals(List.of("CREATE DATABASE `example`", "CREATE TABLE `example`.`items` (`id` INT)"), Schema.parse(VALID).statements());
    }
    @Test void acceptsFencedJSON() { assertEquals("example", Schema.parse("```json\n" + VALID + "\n```").databaseName()); }
    @ParameterizedTest @ValueSource(strings={"bad-name", "name;DROP", "", "1bad", "x`y"})
    void rejectsUnsafeIdentifiers(String name) { assertThrows(IllegalArgumentException.class, () -> Schema.identifier(name)); }
    @ParameterizedTest @ValueSource(strings={"INT; DROP TABLE x", "VARCHAR(0)", "DECIMAL(2,3)", "VARCHAR(99999)", "INT PRIMARY KEY", "unknown"})
    void rejectsUnvalidatedTypes(String type) { assertThrows(IllegalArgumentException.class, () -> Schema.sqlType(type)); }
    @Test void acceptsBoundedTypes() { assertEquals("VARCHAR(200)", Schema.sqlType("varchar(200)")); assertEquals("DECIMAL(10,2)", Schema.sqlType("DECIMAL(10,2)")); }
    @ParameterizedTest @ValueSource(strings={"null", "{}", "{\"databaseName\":\"a\",\"tables\":[]}", "not json", "{} {}"})
    void rejectsMalformedDesigns(String raw) { assertThrows(IllegalArgumentException.class, () -> Schema.parse(raw)); }
    @Test void rejectsDuplicateNames() {
        String duplicate = VALID.replace("\"type\":\"INT\"}", "\"type\":\"INT\"},{\"columnName\":\"ID\",\"type\":\"INT\"}");
        assertThrows(IllegalArgumentException.class, () -> Schema.parse(duplicate));
    }
    @Test void requestEscapesQuotesAndNewlines() {
        String description = "A shop named \"Example\"\nwith orders";
        String text = JsonParser.parseString(AIDatabaseBuilder.requestBody(description)).getAsJsonObject()
            .getAsJsonArray("contents").get(0).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().get("text").getAsString();
        assertTrue(text.endsWith(description));
    }
    @Test void parsesProviderResponse() {
        JsonObject part = new JsonObject(); part.addProperty("text", VALID);
        JsonArray parts = new JsonArray(); parts.add(part);
        JsonObject content = new JsonObject(); content.add("parts", parts);
        JsonObject candidate = new JsonObject(); candidate.add("content", content);
        JsonArray candidates = new JsonArray(); candidates.add(candidate);
        JsonObject body = new JsonObject(); body.add("candidates", candidates);
        assertEquals("example", AIDatabaseBuilder.responseDesign(body.toString()).databaseName());
        assertThrows(IllegalArgumentException.class, () -> AIDatabaseBuilder.responseDesign("{}"));
    }
    @Test void offlineExampleNeverRequiresConfiguration() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, AIDatabaseBuilder.run(new String[]{"--example"}, new Scanner(""), new PrintStream(output), System.err));
        assertTrue(output.toString().contains("No database connection was made"));
    }
    @Test void canceledApprovalNeverConnects() {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        assertEquals(0, AIDatabaseBuilder.run(new String[]{"--example", "--apply"}, new Scanner("no\n"), new PrintStream(output), System.err));
        assertTrue(output.toString().contains("Canceled"));
    }
    @Test void executionStopsAtFirstDatabaseFailure() {
        int[] attempts = {0};
        Statement statement = (Statement) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Statement.class}, (p,m,a) -> {
            if (m.getName().equals("executeUpdate")) { attempts[0]++; throw new SQLException("Fictional failure", "42000"); }
            return null;
        });
        Connection connection = (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{Connection.class}, (p,m,a) -> statement);
        assertThrows(SQLException.class, () -> AIDatabaseBuilder.execute(connection, Schema.parse(VALID)));
        assertEquals(1, attempts[0]);
    }
}
