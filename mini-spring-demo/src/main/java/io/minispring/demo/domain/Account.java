package io.minispring.demo.domain;

import java.math.BigDecimal;

public record Account(long id, String owner, BigDecimal balance) {
}
