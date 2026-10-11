package dev.portfolio.ledger.account;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import dev.portfolio.ledger.account.AccountDtos.AccountResponse;
import dev.portfolio.ledger.account.AccountDtos.CreateAccountRequest;
import jakarta.validation.Valid;

@RestController
@RequestMapping("/accounts")
class AccountController {

    private final AccountService service;

    AccountController(AccountService service) {
        this.service = service;
    }

    @PostMapping
    ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        AccountResponse account = service.create(request.label());
        URI location = ServletUriComponentsBuilder.fromCurrentRequestUri()
                .path("/{id}").buildAndExpand(account.id()).toUri();
        return ResponseEntity.created(location).body(account);
    }

    @GetMapping("/{id}")
    AccountResponse get(@PathVariable UUID id) {
        return service.get(id);
    }
}
