package io.minispring.demo.domain;

import java.math.BigDecimal;

public record TransferRequest(long fromId, long toId, BigDecimal amount) {
}
