package io.minispring.tx;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Plain JDBC without the boilerplate. Every call takes its connection from
 * {@link TransactionContext}, so inside a transaction all statements share the transaction's
 * connection, and outside one each statement gets a connection of its own (auto-commit).
 * Closing is done here, never by the caller; checked {@link SQLException}s become
 * {@link DataAccessException}.
 */
public final class JdbcTemplate {

    private final DataSource dataSource;

    public JdbcTemplate(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Runs an INSERT, UPDATE, DELETE or DDL statement; returns the affected row count. */
    public int update(String sql, Object... args) {
        return statement(sql, false, args, PreparedStatement::executeUpdate);
    }

    /** Runs an INSERT and returns the first generated key. */
    public long insertReturningKey(String sql, Object... args) {
        return statement(sql, true, args, statement -> {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                if (!keys.next()) {
                    throw new DataAccessException("No generated key returned; SQL [" + sql + "]");
                }
                return keys.getLong(1);
            }
        });
    }

    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        return statement(sql, false, args, statement -> {
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.mapRow(rs, rows.size()));
                }
                return rows;
            }
        });
    }

    /** The only row, or empty; more than one row is an error. */
    public <T> Optional<T> queryForOptional(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = query(sql, mapper, args);
        if (rows.size() > 1) {
            throw new DataAccessException("Expected at most one row but got " + rows.size() + "; SQL [" + sql + "]");
        }
        return rows.stream().findFirst();
    }

    public long queryForLong(String sql, Object... args) {
        return queryForOptional(sql, (rs, row) -> rs.getLong(1), args)
                .orElseThrow(() -> new DataAccessException("Expected one row but got none; SQL [" + sql + "]"));
    }

    @FunctionalInterface
    private interface StatementCallback<T> {
        T run(PreparedStatement statement) throws SQLException;
    }

    private <T> T statement(String sql, boolean generatedKeys, Object[] args, StatementCallback<T> callback) {
        Connection connection = null;
        try {
            connection = TransactionContext.connection(dataSource);
            try (PreparedStatement statement = generatedKeys
                    ? connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
                    : connection.prepareStatement(sql)) {
                for (int i = 0; i < args.length; i++) {
                    statement.setObject(i + 1, args[i]);
                }
                return callback.run(statement);
            }
        } catch (SQLException e) {
            throw new DataAccessException("JDBC call failed", sql, e);
        } finally {
            if (connection != null) {
                TransactionContext.release(connection, dataSource);
            }
        }
    }
}
