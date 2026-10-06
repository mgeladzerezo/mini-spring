package io.minispring.demo.repository;

import java.util.List;
import java.util.Optional;

/**
 * A repository for entities of type {@code T} with identifiers of type {@code ID}. Consumers ask for
 * {@code Repository<Account, Long>} or {@code Repository<Transfer, Long>} and the container picks the
 * right bean by resolving the type arguments through the implementations' class hierarchies.
 */
public interface Repository<T, ID> {

    Optional<T> findById(ID id);

    List<T> findAll();

    T save(T entity);

    long count();
}
