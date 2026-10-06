package io.minispring.demo.web;

import io.minispring.demo.domain.Transfer;
import io.minispring.demo.domain.TransferRequest;
import io.minispring.demo.repository.TransferRepository;
import io.minispring.demo.service.AuditLog;
import io.minispring.demo.service.TransferService;
import io.minispring.web.annotation.GetMapping;
import io.minispring.web.annotation.PostMapping;
import io.minispring.web.annotation.RequestBody;
import io.minispring.web.annotation.RequestMapping;
import io.minispring.web.annotation.RequestParam;
import io.minispring.web.annotation.RestController;
import io.minispring.web.http.ResponseEntity;
import java.net.URI;
import java.util.List;

@RestController
@RequestMapping("/api")
public class TransferController {

    private final TransferService service;
    private final TransferRepository transfers;
    private final AuditLog audit;

    public TransferController(TransferService service, TransferRepository transfers, AuditLog audit) {
        this.service = service;
        this.transfers = transfers;
        this.audit = audit;
    }

    @PostMapping("/transfers")
    public ResponseEntity<Transfer> transfer(@RequestBody TransferRequest request) {
        Transfer done = service.transfer(request.fromId(), request.toId(), request.amount());
        return ResponseEntity.created(URI.create("/api/transfers/" + done.id()), done);
    }

    @GetMapping("/transfers")
    public List<Transfer> latest(@RequestParam(defaultValue = "20") int limit) {
        return transfers.latest(Math.max(1, Math.min(limit, 200)));
    }

    @GetMapping("/audit")
    public List<AuditLog.Entry> audit() {
        return audit.entries();
    }
}
