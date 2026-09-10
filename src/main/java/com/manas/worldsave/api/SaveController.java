package com.manas.worldsave.api;

import com.manas.worldsave.api.dto.BeginSaveRequest;
import com.manas.worldsave.api.dto.FencedRequest;
import com.manas.worldsave.api.dto.UploadChunkRequest;
import com.manas.worldsave.lease.StaleLeaseException;
import com.manas.worldsave.save.ChunkChecksumMismatchException;
import com.manas.worldsave.save.NoSuchVersionException;
import com.manas.worldsave.save.RestoreResult;
import com.manas.worldsave.save.SaveService;
import com.manas.worldsave.save.WholeObjectHashMismatchException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SaveController {

    private final SaveService saveService;

    public SaveController(SaveService saveService) {
        this.saveService = saveService;
    }

    @PostMapping("/worlds/{worldId}/saves")
    public String begin(@PathVariable String worldId, @RequestBody BeginSaveRequest request) {
        return saveService.beginSave(worldId, request.sessionId(), request.fencingToken(), request.totalChunks());
    }

    @PostMapping("/worlds/{worldId}/saves/{versionId}/chunks")
    public String uploadChunk(@PathVariable String worldId, @PathVariable String versionId,
                               @RequestBody UploadChunkRequest request) {
        return saveService.uploadChunk(worldId, request.sessionId(), request.fencingToken(), versionId,
                request.chunkIndex(), request.data(), request.checksum());
    }

    @GetMapping("/worlds/{worldId}/saves/{versionId}/resume-point")
    public int resumePoint(@PathVariable String worldId, @PathVariable String versionId) {
        return saveService.resumePoint(versionId);
    }

    @PostMapping("/worlds/{worldId}/saves/{versionId}/complete")
    public String complete(@PathVariable String worldId, @PathVariable String versionId, @RequestBody FencedRequest request) {
        return saveService.completeSave(worldId, request.sessionId(), request.fencingToken(), versionId);
    }

    @GetMapping("/worlds/{worldId}/saves/{versionId}/restore")
    public byte[] restore(@PathVariable String worldId, @PathVariable String versionId) {
        RestoreResult result = saveService.restore(worldId, versionId);
        return result.data();
    }

    @PostMapping("/worlds/{worldId}/rollback/{versionId}")
    public byte[] rollback(@PathVariable String worldId, @PathVariable String versionId, @RequestBody FencedRequest request) {
        RestoreResult result = saveService.rollback(worldId, request.sessionId(), request.fencingToken(), versionId);
        return result.data();
    }

    @ExceptionHandler(StaleLeaseException.class)
    public ResponseEntity<String> handleStale(StaleLeaseException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(e.getMessage());
    }

    @ExceptionHandler(ChunkChecksumMismatchException.class)
    public ResponseEntity<String> handleChecksum(ChunkChecksumMismatchException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
    }

    @ExceptionHandler(WholeObjectHashMismatchException.class)
    public ResponseEntity<String> handleWholeHash(WholeObjectHashMismatchException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(e.getMessage());
    }

    @ExceptionHandler(NoSuchVersionException.class)
    public ResponseEntity<String> handleNoSuchVersion(NoSuchVersionException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(e.getMessage());
    }
}
