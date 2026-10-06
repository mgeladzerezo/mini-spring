package io.minispring.demo.repository;

import io.minispring.core.annotation.Repository;
import io.minispring.demo.domain.Account;
import io.minispring.tx.JdbcTemplate;
import java.math.BigDecimal;

@Repository
public class AccountRepository extends JdbcRepository<Account, Long> {

    public AccountRepository(JdbcTemplate jdbc) {
        super(jdbc, "account", (rs, row) -> new Account(rs.getLong("id"), rs.getString("owner"),
                rs.getBigDecimal("balance")));
    }

    @Override
    public Account save(Account account) {
        long id = jdbc.insertReturningKey("insert into account(owner, balance) values (?, ?)", account.owner(),
                account.balance());
        return new Account(id, account.owner(), account.balance());
    }

    /**
     * Adds {@code delta} to the balance in one atomic statement that refuses to go below zero.
     *
     * @return whether the account exists and the new balance is not negative
     */
    public boolean adjustBalance(long id, BigDecimal delta) {
        return jdbc.update("update account set balance = balance + ? where id = ? and balance + ? >= 0",
                delta, id, delta) == 1;
    }
}
