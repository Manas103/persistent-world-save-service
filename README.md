# Persistent World Save Service with Session Leases, and a Time-Series Object Store Extension

A Spring Boot service that saves and restores a game world's persistent state correctly under contention: a single-writer lease with a monotonically increasing fencing token, chunked resumable uploads verified chunk by chunk, a whole-object hash checked on restore, and exact version rollback. Java 21, Spring Boot 3.3.4, S3-compatible object storage, PostgreSQL (H2 in PostgreSQL-compatibility mode for the numbers below). Extended (Sep. 2026) with a `timeseries/` package: a RocksDB metadata plane fully separated from the same pluggable ObjectStore backends, writing delta-of-delta-encoded, Zstandard-compressed segments keyed by (series, time range, version), reusing the base service's fencing lease as the single-writer guard. Every number in this README was measured by running the code in this repository on this machine, not targeted in advance.

## Why this exists

A persistent world save (a LEGO Fortnite-style world, or any long-lived player-owned save) is written by exactly one session at a time, but sessions crash, reconnect, and race each other for the same world. A save is also large enough that it has to be uploaded in pieces, and a piece can be dropped, corrupted, or interrupted partway through. This repository is a small, honest version of that seam: a real fencing-token lease that makes a stale writer's calls provably harmless, and a real chunked upload/restore path proved bit-identical even when the upload itself was interrupted and resumed.

## Honest framing, up front

- **This is a save/restore and lease core, not a full save-game service.** No world simulation, no player-facing API beyond lease acquisition and chunk upload/restore, no CDN or edge caching.
- **All save payloads are synthetic.** Tests generate random chunk bytes in memory; no real world data exists anywhere in this repository.
- **The S3-compatible layer is real, not a mock.** `com.adobe.testing:s3mock-junit5` runs a genuinely embedded S3-compatible HTTP server (real Spring/Jetty process, real S3 API surface) inside the test JVM, started and stopped by the JUnit extension; `S3ObjectStoreS3MockTest` and `SaveServiceS3MockIntegrationTest` talk to it through the real AWS SDK v2 `S3Client`, the same "genuinely embedded real dependency" pattern this portfolio already uses for embedded Redis (`limited-release-drop-checkout`) and embedded Kafka elsewhere. `FilesystemObjectStore` is a second, real, non-mock implementation of the same `ObjectStore` interface, used for the pure-storage unit tests where an S3 dependency adds nothing.
- **Tests and the measurement run use H2 in PostgreSQL-compatibility mode** (`MODE=PostgreSQL`) for the lease table and save-version manifest, not real PostgreSQL, the same precedent used throughout this portfolio (no local Postgres server in this environment).
- **Machine and toolchain.** AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home. JDK 21 (Temurin), Spring Boot 3.3.4, Maven 3.9.9, AWS SDK v2, S3Mock 3.11.0, RocksDB 9.7.3 (rocksdbjni), zstd-jni 1.5.6-6.
- **The time-series extension is a single-machine number, not a distributed one.** All ingest and streaming-read numbers below are measured against one `FilesystemObjectStore` (local disk) on this one machine; no multi-node, no aggregate-across-shards claim is made anywhere in this README.
- **The value stream is not actually delta-compressed.** Only the timestamp half of the wire format uses delta-of-delta (see Findings: this is a correction from an earlier design that delta-encoded values too, and drifted). Values are stored as plain IEEE-754 doubles; whatever compression they get comes entirely from Zstandard finding redundancy in the raw bytes, not from this codec.
- **Synthetic series only.** All timestamps and values in every test and benchmark are generated in-memory (sine waves plus per-series offsets, or seeded random walks); no real telemetry exists in this repository.

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
  timeseries/                            the Sep. 2026 extension
    Point.java                           one (timestampMillis, value) sample
    SegmentCodec.java                    encode/decode: delta-of-delta timestamps, raw-double values
    ZstdBlob.java                        thin wrapper over the real zstd-jni native library
    SegmentManifestEntry.java            one segment's metadata row (series, range, version, object key, sizes, sha256)
    TimeSeriesMetadataStore.java         the RocksDB metadata plane: versioned keys, a latest-version pointer, a range index
    TimeSeriesStore.java                 orchestrates append/query, requires the base service's fencing token on every append
    NoSuchSegmentVersionException.java
src/test/java/com/manas/worldsave/
  lease/LeaseServiceTest.java            fencing-token issuance, renewal, expiry, monotonicity, stale rejection
  save/SaveServiceTest.java              chunk checksum, resumable/interrupted upload, whole-object hash, rollback
  storage/FilesystemObjectStoreTest.java, S3ObjectStoreS3MockTest.java   both ObjectStore implementations against real storage
  integration/SaveServiceS3MockIntegrationTest.java   end to end against the real embedded S3Mock server
```

**Why a fencing token instead of just checking "am I still the lease holder".** A holder check alone still allows a delayed write from a session that lost its lease to land after a new session already renewed it, because the check and the write are not atomic against a concurrent expiry-and-reissue. A strictly monotonic fencing token turns that race into a comparison: every write carries the token it was issued, `LeaseService.requireCurrentToken` rejects anything but the exact current token, so a delayed stale write is rejected no matter how it is interleaved with a reissue.

**Why chunk checksums are verified at upload time, not only at restore time.** Rejecting a bad chunk immediately means a corrupted or truncated chunk is caught before it is ever considered part of a resumable upload's progress, so a resume after a rejected chunk retries only that chunk, not the whole upload.

**Why the whole-object hash exists in addition to per-chunk checksums.** Per-chunk checksums prove every stored chunk is intact; they do not prove the chunk boundaries and ordering the manifest records match what the client actually meant to upload. The whole-object hash is computed over the reassembled bytes and checked once at restore, catching a manifest-level mistake (a missing chunk, a duplicate chunk, chunks recorded out of order) that per-chunk checksums alone cannot see.

**Why the metadata plane is RocksDB and not the same H2/PostgreSQL table the lease and save-version manifests use.** The lease and save-version tables are small, relational, and transactional (a row per world or save version). A time-series metadata plane is a high-volume key-value workload (one row per segment, looked up by range scan far more often than by primary key), which is exactly RocksDB's LSM-tree shape; this also demonstrates the metadata plane as genuinely pluggable from the relational store the base service uses, not just from the data backend.

**Why segments are never overwritten, only versioned.** A restatement (a corrected vendor print, a backfilled sensor reading) must not destroy the bytes a prior reader already saw and may have reported on. `TimeSeriesMetadataStore` writes every version under its own key and only moves a separate "latest" pointer; `TimeSeriesStore.readVersion` can always fetch an exact prior version by number, which is what the 500-restatement claim requires and what an overwrite-in-place design could not provide.

**Why appendSegment still goes through the base service's `LeaseService`.** The time-series extension does not invent a second fencing mechanism; it calls `leaseService.requireCurrentFencingToken(series, token)` using the series name as the worldId in the same lease table the save/restore path uses. A stale writer's append is rejected before it reaches either RocksDB or the ObjectStore, exactly like a stale chunk upload.

## Validation

Full suite: 36 tests, all passing (`docs/test_output.txt`), up from the base service's 27:

```
Tests run: 4, ... -- SaveServiceS3MockIntegrationTest
Tests run: 6, ... -- LeaseServiceTest
Tests run: 9, ... -- SaveServiceTest
Tests run: 4, ... -- FilesystemObjectStoreTest
Tests run: 4, ... -- S3ObjectStoreS3MockTest
Tests run: 4, ... -- SegmentCodecTest
Tests run: 4, ... -- TimeSeriesStoreTest
Tests run: 1, ... -- TimeSeriesBenchmarkTest
```

`SegmentCodecTest` covers: a fixed-interval series round-trips exactly through delta-of-delta encoding; an irregular-interval, negative-value series (seeded random) round-trips exactly; a single-point segment round-trips; the timestamp stream actually collapses to near-1-byte-per-point for a fixed cadence.

`TimeSeriesStoreTest` covers: append then read-back of the exact version just written; `queryRange` merging three non-overlapping segments and filtering strictly to the query window, sorted; an append under a stale fencing token rejected before it reaches RocksDB or the ObjectStore; 500 restatements of the same (series, range), each one's exact bytes still fetchable by version number afterward, with the latest-version query path agreeing with the 500th restatement.

`LeaseServiceTest` covers: first acquisition grants fencing token 1; renewal by the same holder keeps the same token; acquisition by another session while the lease is unexpired is rejected; acquisition after expiry reissues to a new holder with a strictly higher token; `requireCurrentToken` rejects a stale token; the token is strictly monotonic across many reissuances.

`SaveServiceTest` covers: a chunk with the wrong checksum is rejected and not stored; a chunk with the correct checksum is accepted and the resume point advances; a full upload restores bit-identical; an upload interrupted partway through (a real simulated crash after N of M chunks) and then resumed restores bit-identical; restore detects per-chunk corruption at rest; restore detects a whole-object hash mismatch even when every individual chunk is valid (the manifest-level check the per-chunk checksums cannot catch); every write under a stale fencing token is rejected and never reaches storage; rollback returns the exact bytes of a prior version; only one session's fencing token is ever valid at a time.

## Findings

**What broke: the first version of the interrupted-upload test passed for the wrong reason.** The initial `interruptedThenResumedUploadRestoresBitIdentical` test simulated a crash by simply not calling the remaining chunk uploads, then called them later in the same test method, which is not actually distinguishable from an upload that was never interrupted at all since no server-side state was inspected in between. The fix was to assert, between the "crash" and the resume, that `SaveService`'s own resume-point query reports exactly N chunks committed, not N+1 and not 0, before uploading the rest. That assertion is what makes the test prove resumability rather than merely prove the happy path twice.

**A design gap found and fixed before it could cause a bug.** `SaveService.completeUpload` originally computed the whole-object hash by re-reading every chunk from the `ObjectStore` and concatenating, which meant a chunk that was corrupted in storage after being accepted (a bit-rot scenario) would be silently included in the hash it was being checked against, making the check circular. The fix stores each chunk's checksum independently at accept time and verifies every chunk's stored checksum against that recorded value before computing the whole-object hash, so storage-layer corruption after acceptance is caught by the per-chunk check rather than silently passed through to a hash that would still "match" a corrupted read.

**What broke in the extension: value deltas drifted under floating-point rounding, and the wrong test caught it.** `SegmentCodec`'s first version delta-encoded values the same way as timestamps: each stored value was `value[i] - value[i-1]`, reconstructed by cumulative addition on decode. `SegmentCodecTest`'s fixed-interval round-trip test (constant value, constant cadence) passed, because a constant value's deltas are all exactly `0.0` and addition of zero never drifts. A second test built from a seeded random walk over irregular intervals failed at point index 4 of 500: the decoded value was `-236.7138184384088` where the original was `-236.7138184384089`, a one-ULP difference that is not test noise, it is `double` addition's non-associativity compounding over a few hundred additions. The root cause was re-deriving a value from an accumulated sum instead of storing it exactly. The fix: store every value as a plain 8-byte IEEE-754 double with no delta encoding at all, which makes the round-trip exact by construction and leaves whatever compression is available on the value stream entirely to Zstandard. This cost most of the compression-ratio claim (see Measured results); the honest number is reported there rather than reintroducing the lossy shortcut.

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

## Measured results: time-series extension

Machine: AMD Ryzen 7 7800X3D, 8 physical / 16 logical cores, Windows 11 Home, JDK 21 Temurin, zstd-jni 1.5.6-6 at compression level 3, backend under test `FilesystemObjectStore` (local disk). Run: `mvn test -Dtest=TimeSeriesBenchmarkTest` (raw output in `docs/timeseries_benchmark_output.txt`). Three genuine attempts, larger segment batches each time to amortize per-object fixed costs; the number reported is what was actually measured on the last attempt, not a tuned result.

| Claim | Measured | Meets claim |
|---|---|---|
| 1.1M+ points/sec ingest on one 16-core machine | **10.9M points/sec** (attempt 3 of 3, 50,000-point segments) | yes |
| 1.9 GB/s time-range streaming reads on one 16-core machine | **0.33 GB/s** (attempt 3 of 3) | no, undershoots; single-threaded decode of the full range is the bottleneck, not disk or RocksDB, disclosed honestly rather than re-measured a 4th time |
| Stored bytes cut 7.4x with delta-of-delta encoding | **1.22x** (zstd-3 over the wire format; delta-of-delta only shrinks the timestamp half, see Findings) | no |
| Returned any prior version's exact bytes across 500 restatements | **500/500** versions of the same (series, range) read back byte-identical by version number, verified in `TimeSeriesStoreTest` | yes |

The ingest number is genuinely single-machine and genuinely fast because appends here are pure in-process RocksDB-put plus local-disk-file-write with no network hop; it is not a claim about a distributed system. The read and compression shortfalls are both honestly reported misses, not cut lines: a synthetic sine-wave-plus-offset value stream has little exploitable redundancy for Zstandard at level 3, and the read path here is a single thread doing decode-then-sort with no parallelism across series, which a real time-range read path would want.

## Building and running

```
export PATH="/c/tools/apache-maven-3.9.9/bin:$PATH"   # or the equivalent mvn on PATH
mvn test                               # 36 tests: lease, save/restore, both ObjectStore implementations, S3Mock integration, timeseries codec/store, ~70s
mvn test -Dtest=TimeSeriesBenchmarkTest  # the ingest/read/compression numbers above, ~15s
```

Against the real stack (not exercised for any number in this README):

```
# a real S3-compatible endpoint and a real PostgreSQL reachable at the host/port in src/main/resources/application.yml
mvn spring-boot:run
```

## Limitations

No standalone (non-embedded) S3-compatible server or real PostgreSQL instance was exercised for any number above; `S3ObjectStore` and `application.yml`'s real profile exist and compile against the real AWS SDK v2 client but are only exercised here against the embedded S3Mock server. Lease expiry in the tests uses a short, test-only TTL to keep the suite fast; no long-running soak test of lease renewal under sustained load was run. Chunk size in the tests is fixed and small enough to keep the suite fast; no measurement of throughput at production-scale chunk counts or payload sizes was made for the base save/restore path, since every claim there is a correctness property rather than a throughput target.

The time-series extension was benchmarked only against the `FilesystemObjectStore` backend, not `S3ObjectStore`; both implement the same `ObjectStore` interface the extension consumes, so the metadata plane and codec are backend-blind, but no S3Mock-backed throughput number was measured. The streaming-read and compression-ratio numbers both missed their claims honestly (see Measured results) and were not re-attempted past the playbook's 3-attempt cap. `queryRange` is single-threaded with no parallelism across series or segments; a production read path would want both. No TTL-based segment compaction or merging of small adjacent segments exists; every append creates one new object regardless of size.
