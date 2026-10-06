package io.minispring.demo.service;

import io.minispring.aop.aspects.Retry;
import io.minispring.aop.aspects.Timed;
import io.minispring.core.annotation.Service;
import io.minispring.core.event.ApplicationEventPublisher;
import io.minispring.demo.domain.Account;
import io.minispring.demo.domain.InsufficientFundsException;
import io.minispring.demo.domain.NoSuchAccountException;
import io.minispring.demo.domain.Transfer;
import io.minispring.demo.domain.TransferCompleted;
import io.minispring.demo.repository.AccountRepository;
import io.minispring.demo.repository.Repository;
import io.minispring.tx.DataAccessException;
import io.minispring.tx.Transactional;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Moves money between accounts. The class implements no interface, so the container instantiates a
 * generated subclass proxy of it and the three annotations below are applied by interceptors:
 * <ol>
 *   <li>{@code @Retry} (outermost): a deadlock or lock timeout between two concurrent transfers is
 *       retried, each attempt in a fresh transaction;</li>
 *   <li>{@code @Timed}: the call is measured;</li>
 *   <li>{@code @Transactional}: the debit, the credit and the transfer record commit together or not at all.</li>
 * </ol>
 * When the source id is lower than the target id the debit is executed before the target is known
 * to exist, so a transfer to a missing account demonstrates the rollback of an already executed update.
 */
@Service
public class TransferService {

    private final AccountRepository accounts;
    private final Repository<Transfer, Long> transfers;
    private final ApplicationEventPublisher events;

    public TransferService(AccountRepository accounts, Repository<Transfer, Long> transfers,
                           ApplicationEventPublisher events) {
        this.accounts = accounts;
        this.transfers = transfers;
        this.events = events;
    }

    @Retry(maxAttempts = 5, delayMillis = 20, on = DataAccessException.class)
    @Timed
    @Transactional
    public Transfer transfer(long fromId, long toId, BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("The amount must be positive");
        }
        if (fromId == toId) {
            throw new IllegalArgumentException("Source and target account must differ");
        }
        accounts.findById(fromId).orElseThrow(() -> new NoSuchAccountException(fromId));
        // Row locks are taken in ascending account id order whatever the direction of the transfer, so two
        // opposite transfers between the same accounts cannot each hold the lock the other one waits for.
        if (fromId < toId) {
            debit(fromId, amount);
            credit(toId, amount); // a missing target rolls the debit above back
        } else {
            credit(toId, amount);
            debit(fromId, amount);
        }
        Transfer stored = transfers.save(new Transfer(0, fromId, toId, amount, Instant.now(), "COMPLETED"));
        events.publishEvent(new TransferCompleted(stored));
        return stored;
    }

    private void debit(long accountId, BigDecimal amount) {
        if (!accounts.adjustBalance(accountId, amount.negate())) {
            throw new InsufficientFundsException(accountId);
        }
    }

    private void credit(long accountId, BigDecimal amount) {
        if (!accounts.adjustBalance(accountId, amount)) {
            throw new NoSuchAccountException(accountId);
        }
    }

    @Transactional(readOnly = true)
    public BigDecimal totalBalance() {
        return accounts.findAll().stream().map(Account::balance).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
