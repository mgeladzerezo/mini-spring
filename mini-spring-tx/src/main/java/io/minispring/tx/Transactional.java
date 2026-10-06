package io.minispring.tx;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Runs a method (or every public method of a class) in a transaction.
 *
 * <p>It only has an effect when the call crosses a proxy, see {@link EnableTransactionManagement}:
 * a method calling another method on {@code this} through an interface proxy bypasses it.
 *
 * <p>By default a transaction is rolled back for {@link RuntimeException} and {@link Error}, and
 * committed for checked exceptions, as in Spring: a checked exception is a business outcome the
 * caller is expected to handle, not necessarily a failure of the unit of work.
 */
@Target({ElementType.METHOD, ElementType.TYPE, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface Transactional {

    Propagation propagation() default Propagation.REQUIRED;

    /** Applied only when this call starts the transaction; ignored when joining one. */
    Isolation isolation() default Isolation.DEFAULT;

    /** A hint to the driver, applied only when this call starts the transaction. */
    boolean readOnly() default false;

    /** Exception types that also cause a rollback; subclasses count. */
    Class<? extends Throwable>[] rollbackFor() default {};

    /** Exception types that must not cause a rollback, even if they would by default. */
    Class<? extends Throwable>[] noRollbackFor() default {};
}
