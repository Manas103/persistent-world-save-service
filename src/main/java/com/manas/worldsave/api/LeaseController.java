package com.manas.worldsave.api;

import com.manas.worldsave.api.dto.AcquireLeaseRequest;
import com.manas.worldsave.api.dto.LeaseGrantResponse;
import com.manas.worldsave.lease.LeaseConflictException;
import com.manas.worldsave.lease.LeaseService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

@RestController
public class LeaseController {

    private final LeaseService leaseService;

    public LeaseController(LeaseService leaseService) {
        this.leaseService = leaseService;
    }

    @PostMapping("/worlds/{worldId}/lease")
    public LeaseGrantResponse acquire(@PathVariable String worldId, @RequestBody AcquireLeaseRequest request) {
        var grant = leaseService.acquire(worldId, request.sessionId(), Duration.ofSeconds(request.ttlSeconds()));
        return LeaseGrantResponse.from(grant);
    }

    @ExceptionHandler(LeaseConflictException.class)
    public ResponseEntity<String> handleConflict(LeaseConflictException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }
}
