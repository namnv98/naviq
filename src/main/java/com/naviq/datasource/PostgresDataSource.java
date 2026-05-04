package com.naviq.datasource;

import java.sql.Connection;
import java.sql.DriverManager;

import static java.util.Objects.isNull;

public class PostgresDataSource {
    private static Connection conn;

    public static void init() throws Exception {
        String host = System.getProperty("DB_HOST");
        String port = System.getProperty("DB_PORT");
        String db = System.getProperty("DB_DBNAME");
        String user = System.getProperty("DB_USER");
        String pass = System.getProperty("DB_PASSWORD");

        if (host == null || port == null || db == null || user == null || pass == null) {
            throw new RuntimeException(
                    "Missing env. Required: DB_HOST, DB_PORT, DB_NAME, DB_USER, DB_PASSWORD"
            );
        }

        String url = "jdbc:postgresql://" + host + ":" + port + "/" + db;

        conn = DriverManager.getConnection(url, user, pass);
    }

    public static Connection get() {
        if (isNull(conn)) {
            try {
                init();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        return conn;
    }

    public static void close() throws Exception {
        if (conn != null) conn.close();
    }
}