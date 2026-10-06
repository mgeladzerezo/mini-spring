package io.minispring.tx;

import io.minispring.aop.Advisor;
import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.Configuration;
import javax.sql.DataSource;

/** Beans contributed by {@link EnableTransactionManagement}; requires a {@link DataSource} bean. */
@Configuration
public class TransactionConfiguration {

    @Bean
    public TransactionManager transactionManager(DataSource dataSource) {
        return new TransactionManager(dataSource);
    }

    @Bean
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    /** The whole integration with AOP: one advisor bean, picked up by the auto-proxy creator. */
    @Bean
    public Advisor transactionAdvisor(TransactionManager manager) {
        return Advisor.forAnnotation(Transactional.class, new TransactionInterceptor(manager),
                Advisor.TRANSACTION_ORDER);
    }
}
