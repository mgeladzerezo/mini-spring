package io.minispring.demo.domain;

import java.math.BigDecimal;
import java.time.Instant;

public record Transfer(long id, long fromId, long toId, BigDecimal amount, Instant at, String status) {
}
