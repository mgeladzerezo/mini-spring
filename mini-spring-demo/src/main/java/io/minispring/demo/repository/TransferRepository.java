package io.minispring.demo.repository;

import io.minispring.core.annotation.Repository;
import io.minispring.demo.domain.Transfer;
import io.minispring.tx.JdbcTemplate;
import io.minispring.tx.RowMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Repository
public class TransferRepository extends JdbcRepository<Transfer, Long> {

    private static final RowMapper<Transfer> MAPPER = (rs, row) -> new Transfer(rs.getLong("id"),
            rs.getLong("from_id"), rs.getLong("to_id"), rs.getBigDecimal("amount"),
            rs.getObject("at", OffsetDateTime.class).toInstant(), rs.getString("status"));

    public TransferRepository(JdbcTemplate jdbc) {
        super(jdbc, "transfer", MAPPER);
    }

    @Override
    public Transfer save(Transfer transfer) {
        long id = jdbc.insertReturningKey("insert into transfer(from_id, to_id, amount, at, status) values (?,?,?,?,?)",
                transfer.fromId(), transfer.toId(), transfer.amount(), transfer.at().atOffset(ZoneOffset.UTC),
                transfer.status());
        return new Transfer(id, transfer.fromId(), transfer.toId(), transfer.amount(), transfer.at(), transfer.status());
    }

    public List<Transfer> latest(int limit) {
        return jdbc.query("select * from transfer order by id desc limit ?", MAPPER, limit);
    }
}
