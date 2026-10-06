package io.minispring.demo.domain;

import java.math.BigDecimal;

public record CreateAccountRequest(String owner, BigDecimal openingBalance) {
}
