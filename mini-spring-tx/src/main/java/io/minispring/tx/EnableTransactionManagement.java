package io.minispring.tx;

import io.minispring.aop.EnableAspects;
import io.minispring.core.annotation.Import;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Switches on {@link Transactional}: it enables the aspects infrastructure and imports the
 * transaction manager, a {@link JdbcTemplate} and the advisor. The application supplies a
 * {@code javax.sql.DataSource} bean.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@EnableAspects
@Import(TransactionConfiguration.class)
public @interface EnableTransactionManagement {
}
