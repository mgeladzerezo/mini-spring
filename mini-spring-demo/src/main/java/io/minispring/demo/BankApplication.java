package io.minispring.demo;

import io.minispring.aop.EnableAspects;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.context.MiniApplication;
import io.minispring.tx.EnableTransactionManagement;
import io.minispring.web.mvc.EnableWebMvc;

/**
 * A small bank that uses every part of the framework: generic repositories over JdbcTemplate, a
 * transactional transfer that rolls back on failure, retry and timing aspects, application events,
 * a controller advice, an interceptor and a static UI. Run it and open http://localhost:8202.
 */
@Configuration
@EnableAspects
@EnableTransactionManagement
@EnableWebMvc
public class BankApplication {

    public static void main(String[] args) {
        MiniApplication.run(BankApplication.class, args);
    }
}
