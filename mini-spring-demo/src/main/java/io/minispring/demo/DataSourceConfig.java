package io.minispring.demo;

import io.minispring.core.annotation.Bean;
import io.minispring.core.annotation.Configuration;
import io.minispring.core.annotation.Value;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * The application's database: an in-memory H2 with the schema created at startup. H2's data source
 * opens a new connection per request and does not pool; adequate here, and one more thing the
 * README lists as simpler than a real deployment.
 */
@Configuration
public class DataSourceConfig {

    @Bean
    public DataSource dataSource(@Value("${bank.db.url:jdbc:h2:mem:bank;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000}") String url)
            throws SQLException {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL(url);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("create table if not exists account (id bigint auto_increment primary key, "
                    + "owner varchar(100) not null, balance decimal(19,2) not null check (balance >= 0))");
            statement.execute("create table if not exists transfer (id bigint auto_increment primary key, "
                    + "from_id bigint not null, to_id bigint not null, amount decimal(19,2) not null, "
                    + "at timestamp with time zone not null, status varchar(20) not null)");
        }
        return dataSource;
    }
}
