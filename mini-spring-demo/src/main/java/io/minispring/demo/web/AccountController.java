package io.minispring.demo.web;

import io.minispring.demo.domain.Account;
import io.minispring.demo.domain.CreateAccountRequest;
import io.minispring.demo.domain.NoSuchAccountException;
import io.minispring.demo.repository.Repository;
import io.minispring.web.annotation.GetMapping;
import io.minispring.web.annotation.PathVariable;
import io.minispring.web.annotation.PostMapping;
import io.minispring.web.annotation.RequestBody;
import io.minispring.web.annotation.RequestMapping;
import io.minispring.web.annotation.ResponseStatus;
import io.minispring.web.annotation.RestController;
import io.minispring.web.http.HttpStatus;
import java.util.List;

@RestController
@RequestMapping("/api/accounts")
public class AccountController {

    private final Repository<Account, Long> accounts;

    public AccountController(Repository<Account, Long> accounts) {
        this.accounts = accounts;
    }

    @GetMapping
    public List<Account> list() {
        return accounts.findAll();
    }

    @GetMapping("/{id}")
    public Account get(@PathVariable long id) {
        return accounts.findById(id).orElseThrow(() -> new NoSuchAccountException(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Account create(@RequestBody CreateAccountRequest request) {
        if (request.owner() == null || request.owner().isBlank()) {
            throw new IllegalArgumentException("owner is required");
        }
        if (request.openingBalance() == null || request.openingBalance().signum() < 0) {
            throw new IllegalArgumentException("openingBalance must not be negative");
        }
        return accounts.save(new Account(0, request.owner().strip(), request.openingBalance()));
    }
}
