package io.minispring.tx;

import java.sql.SQLException;

/** Unchecked wrapper for the {@link SQLException} of a {@link JdbcTemplate} call, carrying the SQL. */
public class DataAccessException extends RuntimeException {

    private final String sql;

    public DataAccessException(String message, String sql, SQLException cause) {
        super(message + "; SQL [" + sql + "]; " + cause.getMessage(), cause);
        this.sql = sql;
    }

    public DataAccessException(String message) {
        super(message);
        this.sql = null;
    }

    public String sql() {
        return sql;
    }
}
