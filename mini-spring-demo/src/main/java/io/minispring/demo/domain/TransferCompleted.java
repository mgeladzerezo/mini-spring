package io.minispring.demo.domain;

/** Published after a transfer succeeded; carries the stored transfer. */
public record TransferCompleted(Transfer transfer) {
}
