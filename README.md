# Persistent World Save Service with Session Leases

A Spring Boot service that saves and restores a game world's persistent state correctly under contention: a single-writer lease with a monotonically increasing fencing token, chunked resumable uploads verified chunk by chunk, a whole-object hash checked on restore, and exact version rollback. Java 21, Spring Boot 3.3.4, S3-compatible object storage, PostgreSQL (H2 in PostgreSQL-compatibility mode for the numbers below). Every number in this README was measured by running the code in this repository on this machine, not targeted in advance.

## Why this exists

A persistent world save (a LEGO Fortnite-style world, or any long-lived player-owned save) is written by exactly one session at a time, but sessions crash, reconnect, and race each other for the same world. A save is also large enough that it has to be uploaded in pieces, and a piece can be dropped, corrupted, or interrupted partway through. This repository is a small, honest version of that seam: a real fencing-token lease that makes a stale writer's calls provably harmless, and a real chunked upload/restore path proved bit-identical even when the upload itself was interrupted and resumed.

## Honest framing, up front

- **This is a save/restore and lease core, not a full save-game service.** No world simulation, no player-facing API beyond lease acquisition and chunk upload/restore, no CDN or edge caching.
- **All save payloads are synthetic.** Tests generate random chunk bytes in memory; no real world data exists anywhere in this repository.
- **The S3-compatible layer is real, not a mock.** `com.adobe.testing:s3mock-junit5` runs a genuinely embedded S3-compatible HTTP server (real Spring/Jetty process, real S3 API surface) inside the test JVM, started and stopped by the JUnit extension; `S3ObjectStoreS3MockTest` and `SaveServiceS3MockIntegrationTest` talk to it through the real AWS SDK v2 `S3Client`, the same "genuinely embedded real dependency" pattern this portfolio already uses for embedded Redis (`limited-release-drop-checkout`) and embedded Kafka elsewhere. `FilesystemObjectStore` is a second, real, non-mock implementation of the same `ObjectStore` interface, used for the pure-storage unit tests where an S3 dependency adds nothing.
- **Tests and the measurement run use H2 in PostgreSQL-compatibility mode** (`MODE=PostgreSQL`) for the lease table and save-version manifest, not real PostgreSQL, the same precedent used throughout this portfolio (no local Postgres server in this environment).
- **Machine and toolchain.** AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home. JDK 21 (Temurin), Spring Boot 3.3.4, Maven 3.9.9, AWS SDK v2, S3Mock 3.11.0.

## Architecture

```
src/main/java/com/manas/worldsave/
  WorldSaveApplication.java              Spring Boot entry point
  lease/
    LeaseService.java                    acquire/renew/require: issues and checks fencing tokens
    LeaseEntity.java, LeaseRepository.java   worldId -> current holder, token, expiry
    LeaseConflictException.java, StaleLeaseException.java
  save/
    SaveService.java                     begin save, upload chunk, complete, restore, rollback
    ChunkRecordEntity.java, SaveVersionEntity.java   per-chunk checksum and whole-object manifest
    ChunkChecksumMismatchException.java, WholeObjectHashMismatchException.java
    OutOfOrderChunkException.java, IncompleteUploadException.java, NoSuchVersionException.java
  storage/
    ObjectStore.java                     put/get chunk interface
    S3ObjectStore.java                   real AWS SDK v2 client against an S3-compatible endpoint
    FilesystemObjectStore.java           real local-disk implementation of the same interface
    StorageConfig.java                   picks the active ObjectStore by profile
  api/
    LeaseController.java, SaveController.java, dto/*   REST layer
  util/Sha256.java                       chunk and whole-object hashing
src/test/java/com/manas/worldsave/
  lease/LeaseServiceTest.java            fencing-token issuance, renewal, expiry, monotonicity, stale rejection
  save/SaveServiceTest.java              chunk checksum, resumable/interrupted upload, whole-object hash, rollback
  storage/FilesystemObjectStoreTest.java, S3ObjectStoreS3MockTest.java   both ObjectStore implementations against real storage
  integration/SaveServiceS3MockIntegrationTest.java   end to end against the real embedded S3Mock server
```

**Why a fencing token instead of just checking "am I still the lease holder".** A holder check alone still allows a delayed write from a session that lost its lease to land after a new session already renewed it, because the check and the write are not atomic against a concurrent expiry-and-reissue. A strictly monotonic fencing token turns that race into a comparison: every write carries the token it was issued, `LeaseService.requireCurrentToken` rejects anything but the exact current token, so a delayed stale write is rejected no matter how it is interleaved with a reissue.

**Why chunk checksums are verified at upload time, not only at restore time.** Rejecting a bad chunk immediately means a corrupted or truncated chunk is caught before it is ever considered part of a resumable upload's progress, so a resume after a rejected chunk retries only that chunk, not the whole upload.

**Why the whole-object hash exists in addition to per-chunk checksums.** Per-chunk checksums prove every stored chunk is intact; they do not prove the chunk boundaries and ordering the manifest records match what the client actually meant to upload. The whole-object hash is computed over the reassembled bytes and checked once at restore, catching a manifest-level mistake (a missing chunk, a duplicate chunk, chunks recorded out of order) that per-chunk checksums alone cannot see.

## Validation

Full suite: 27 tests, all passing (`docs/test_output.txt`):

```
Tests run: 4, ... -- SaveServiceS3MockIntegrationTest
Tests run: 6, ... -- LeaseServiceTest
Tests run: 9, ... -- SaveServiceTest
Tests run: 4, ... -- FilesystemObjectStoreTest
Tests run: 4, ... -- S3ObjectStoreS3MockTest
```

`LeaseServiceTest` covers: first acquisition grants fencing token 1; renewal by the same holder keeps the same token; acquisition by another session while the lease is unexpired is rejected; acquisition after expiry reissues to a new holder with a strictly higher token; `requireCurrentToken` rejects a stale token; the token is strictly monotonic across many reissuances.

`SaveServiceTest` covers: a chunk with the wrong checksum is rejected and not stored; a chunk with the correct checksum is accepted and the resume point advances; a full upload restores bit-identical; an upload interrupted partway through (a real simulated crash after N of M chunks) and then resumed restores bit-identical; restore detects per-chunk corruption at rest; restore detects a whole-object hash mismatch even when every individual chunk is valid (the manifest-level check the per-chunk checksums cannot catch); every write under a stale fencing token is rejected and never reaches storage; rollback returns the exact bytes of a prior version; only one session's fencing token is ever valid at a time.

## Findings

**What broke: the first version of the interrupted-upload test passed for the wrong reason.** The initial `interruptedThenResumedUploadRestoresBitIdentical` test simulated a crash by simply not calling the remaining chunk uploads, then called them later in the same test method, which is not actually distinguishable from an upload that was never interrupted at all since no server-side state was inspected in between. The fix was to assert, between the "crash" and the resume, that `SaveService`'s own resume-point query reports exactly N chunks committed, not N+1 and not 0, before uploading the rest. That assertion is what makes the test prove resumability rather than merely prove the happy path twice.

**A design gap found and fixed before it could cause a bug.** `SaveService.completeUpload` originally computed the whole-object hash by re-reading every chunk from the `ObjectStore` and concatenating, which meant a chunk that was corrupted in storage after being accepted (a bit-rot scenario) would be silently included in the hash it was being checked against, making the check circular. The fix stores each chunk's checksum independently at accept time and verifies every chunk's stored checksum against that recorded value before computing the whole-object hash, so storage-layer corruption after acceptance is caught by the per-chunk check rather than silently passed through to a hash that would still "match" a corrupted read.

## Measured results

Machine: AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home, JDK 21 Temurin, Spring Boot 3.3.4. Run: `mvn test` (raw output in `docs/test_output.txt`).

**The headline: every claim below is a correctness property, verified true by a real passing test, not a performance number to hit.**

| Claim | Measured | Meets claim |
|---|---|---|
| Single-writer lease with a fencing token | strictly monotonic token issuance verified across repeated reissuances; concurrent-acquisition race resolves to exactly one holder | yes |
| Chunked resumable uploads verified by per-chunk checksums | wrong-checksum chunk rejected and not stored; correct-checksum chunk accepted and resume point advances; interrupted-then-resumed upload completes and restores bit-identical | yes |
| Whole-object hash checked on restore | restore rejects a case where every chunk checksum is individually valid but the reassembled whole-object hash does not match the manifest | yes |
| Every stale-lease write rejected | every write presenting other than the exact current fencing token is rejected before it reaches `ObjectStore`, verified with a real S3Mock-backed store | yes |
| Restores bit-identical across save cycles including interrupted ones | both a full upload and an interrupted-then-resumed upload restore to byte-identical content against the original in-memory payload | yes |
| Rollback returns the exact prior version | rollback to a named prior version returns exactly that version's bytes, not the current version's | yes |

## Building and running

```
export PATH="/c/tools/apache-maven-3.9.9/bin:$PATH"   # or the equivalent mvn on PATH
mvn test          # 27 tests: lease, save/restore, both ObjectStore implementations, S3Mock integration, ~46s
```

Against the real stack (not exercised for any number in this README):

```
# a real S3-compatible endpoint and a real PostgreSQL reachable at the host/port in src/main/resources/application.yml
mvn spring-boot:run
```

## Limitations

No standalone (non-embedded) S3-compatible server or real PostgreSQL instance was exercised for any number above; `S3ObjectStore` and `application.yml`'s real profile exist and compile against the real AWS SDK v2 client but are only exercised here against the embedded S3Mock server. Lease expiry in the tests uses a short, test-only TTL to keep the suite fast; no long-running soak test of lease renewal under sustained load was run. Chunk size in the tests is fixed and small enough to keep the suite fast; no measurement of throughput at production-scale chunk counts or payload sizes was made, since every claim above is a correctness property rather than a throughput target.
