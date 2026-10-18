package com.example.aidatabase;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.Strictness;
import java.util.*;
import java.util.regex.*;

/** A deliberately small schema format; model output is validated before SQL generation. */
public record Schema(String databaseName, List<Schema.Table> tables) {
    public record Column(String columnName, String type) {}
    public record Table(String tableName, List<Column> columns) {}
    private static final Gson JSON = new GsonBuilder().setStrictness(Strictness.STRICT).create();
    private static final Set<String> SIMPLE_TYPES = Set.of("INT", "INTEGER", "BIGINT", "SMALLINT", "TINYINT",
        "FLOAT", "DOUBLE", "BOOLEAN", "TEXT", "LONGTEXT", "DATE", "DATETIME", "TIMESTAMP");

    public static Schema parse(String text) {
        if (text == null || text.isBlank()) { throw new IllegalArgumentException("Empty schema response."); }
        String raw = text.trim();
        if (raw.startsWith("```")) {
            Matcher fence = Pattern.compile("^```(?:json)?\\s*(.*?)\\s*```$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(raw);
            if (!fence.matches()) { throw new IllegalArgumentException("Invalid JSON code fence."); }
            raw = fence.group(1);
        }
        try {
            Schema schema = JSON.fromJson(raw, Schema.class);
            if (schema == null) { throw new IllegalArgumentException("Expected a schema object."); }
            schema.statements();
            return schema;
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Invalid schema: " + failure.getMessage(), failure);
        }
    }

    static String identifier(String value) {
        if (value == null || !value.matches("[A-Za-z_][A-Za-z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Names must start with a letter/underscore and contain only letters, digits and underscores (max 64 characters).");
        }
        return "`" + value + "`";
    }

    static String sqlType(String value) {
        if (value == null) { throw new IllegalArgumentException("Missing column type."); }
        String type = value.trim().toUpperCase(Locale.ROOT);
        if (SIMPLE_TYPES.contains(type)) { return type; }
        Matcher varchar = Pattern.compile("VARCHAR\\(([1-9][0-9]{0,4})\\)").matcher(type);
        if (varchar.matches() && Integer.parseInt(varchar.group(1)) <= 65535) { return type; }
        Matcher decimal = Pattern.compile("DECIMAL\\(([1-9][0-9]?),([0-9]{1,2})\\)").matcher(type);
        if (decimal.matches()) {
            int precision = Integer.parseInt(decimal.group(1)), scale = Integer.parseInt(decimal.group(2));
            if (precision <= 65 && scale <= 30 && scale <= precision) { return type; }
        }
        throw new IllegalArgumentException("Unsupported column type. Use a documented simple type, VARCHAR(n), or DECIMAL(p,s).");
    }

    public List<String> statements() {
        String database = identifier(databaseName);
        if (tables == null || tables.isEmpty() || tables.size() > 50) {
            throw new IllegalArgumentException("A schema must contain between 1 and 50 tables.");
        }
        List<String> statements = new ArrayList<>();
        statements.add("CREATE DATABASE " + database);
        Set<String> names = new HashSet<>();
        for (Table table : tables) {
            if (table == null || !names.add(table.tableName() == null ? "" : table.tableName().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("Duplicate or missing table.");
            }
            String name = identifier(table.tableName());
            if (table.columns() == null || table.columns().isEmpty() || table.columns().size() > 100) {
                throw new IllegalArgumentException("Each table must contain between 1 and 100 columns.");
            }
            List<String> columns = new ArrayList<>();
            Set<String> columnNames = new HashSet<>();
            for (Column column : table.columns()) {
                if (column == null || !columnNames.add(column.columnName() == null ? "" : column.columnName().toLowerCase(Locale.ROOT))) {
                    throw new IllegalArgumentException("Duplicate or missing column.");
                }
                columns.add(identifier(column.columnName()) + " " + sqlType(column.type()));
            }
            statements.add("CREATE TABLE " + database + "." + name + " (" + String.join(", ", columns) + ")");
        }
        return List.copyOf(statements);
    }
}
