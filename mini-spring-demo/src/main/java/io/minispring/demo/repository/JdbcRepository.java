package io.minispring.demo.repository;

import io.minispring.tx.JdbcTemplate;
import io.minispring.tx.RowMapper;
import java.util.List;
import java.util.Optional;

/** Behaviour shared by all JDBC repositories: lookups by id, listing and counting for one table. */
public abstract class JdbcRepository<T, ID> implements Repository<T, ID> {

    protected final JdbcTemplate jdbc;
    private final String table;
    private final RowMapper<T> mapper;

    protected JdbcRepository(JdbcTemplate jdbc, String table, RowMapper<T> mapper) {
        this.jdbc = jdbc;
        this.table = table;
        this.mapper = mapper;
    }

    @Override
    public Optional<T> findById(ID id) {
        return jdbc.queryForOptional("select * from " + table + " where id = ?", mapper, id);
    }

    @Override
    public List<T> findAll() {
        return jdbc.query("select * from " + table + " order by id", mapper);
    }

    @Override
    public long count() {
        return jdbc.queryForLong("select count(*) from " + table);
    }
}
