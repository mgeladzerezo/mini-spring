package io.minispring.demo.service;

import io.minispring.core.annotation.Component;
import io.minispring.core.annotation.PostConstruct;
import io.minispring.core.annotation.Value;
import io.minispring.tx.JdbcTemplate;
import java.math.BigDecimal;

/** Creates three demo accounts when the database is empty. {@code bank.opening-balance} sets their balance. */
@Component
public class DataSeeder {

    private final JdbcTemplate jdbc;
    private final BigDecimal openingBalance;

    public DataSeeder(JdbcTemplate jdbc, @Value("${bank.opening-balance:1000.00}") BigDecimal openingBalance) {
        this.jdbc = jdbc;
        this.openingBalance = openingBalance;
    }

    @PostConstruct
    void seed() {
        if (jdbc.queryForLong("select count(*) from account") == 0) {
            for (String owner : new String[] {"Ada Lovelace", "Alan Turing", "Grace Hopper"}) {
                jdbc.update("insert into account(owner, balance) values (?, ?)", owner, openingBalance);
            }
        }
    }
}
