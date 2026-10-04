package com.github.guoyyit.pointeranalysisjava.persistence;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

/**
 * Durable storage for published snapshots, the applied-revision marker and the
 * append-only query history.
 *
 * <p>Snapshots are written to a temporary file and atomically moved into
 * {@code snapshot.json}, so a crash at any point leaves either the previous
 * complete snapshot or the new one, never a torn file. Revision numbers are
 * monotonic; a recovered snapshot's revision prevents an incremental change
 * being applied twice. Query history is an append-only JSON-lines file, so
 * every answer that was visible before a crash remains available afterwards.
 */
public final class RevisionStore {

    /** Points in the durable publication protocol where a crash can be simulated. */
    public enum PublishPoint {
        /** Before the new snapshot temp file is written. */
        BEFORE_SNAPSHOT_WRITE,
        /** After the snapshot is atomically in place, before the revision marker is fsynced. */
        AFTER_SNAPSHOT_BEFORE_MARKER,
        /** After both snapshot and marker are durable. */
        AFTER_MARKER
    }

    /**
     * Test hook fired at each durable publication point. Throwing from the
     * hook simulates an immediate process crash: no in-memory state is rolled
     * forward. Production code never registers a hook.
     */
    public interface PublishFailureHook {
        void checkpoint(PublishPoint point, long revision);
    }

    /** Thrown by an injected failure hook; treated as a simulated hard crash. */
    public static final class InjectedCrash extends RuntimeException {
        public InjectedCrash(String message) {
            super(message);
        }
    }

    private static final String SNAPSHOT = "snapshot.json";
    private static final String HISTORY = "query-history.jsonl";
    private static final String REVISION = "revision";

    private final Path storageDir;
    private final ObjectMapper mapper;
    private final AtomicLong sequence = new AtomicLong();
    private volatile PublishFailureHook failureHook;

    /** Registers (or clears with {@code null}) a crash-injection hook. */
    public void setFailureHook(PublishFailureHook hook) {
        this.failureHook = hook;
    }

    public RevisionStore(Path storageDir) {
        this.storageDir = storageDir;
        this.mapper = new ObjectMapper()
                .findAndRegisterModules()
                .registerModule(new AnalysisJacksonModule())
                .disable(SerializationFeature.INDENT_OUTPUT)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        try {
            Files.createDirectories(storageDir);
            if (Files.exists(historyPath())) {
                try (Stream<String> lines = Files.lines(historyPath(), StandardCharsets.UTF_8)) {
                    long count = lines.filter(line -> !line.isBlank()).count();
                    sequence.set(count);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("cannot initialize storage at " + storageDir, e);
        }
    }

    public synchronized void saveSnapshot(PointerAnalysisSnapshot snapshot) throws IOException {
        PublishFailureHook hook = failureHook;
        if (hook != null) {
            hook.checkpoint(PublishPoint.BEFORE_SNAPSHOT_WRITE, snapshot.revision());
        }
        Path tmp = storageDir.resolve(SNAPSHOT + ".tmp");
        byte[] bytes = mapper.writeValueAsBytes(snapshot);
        Files.write(tmp, bytes, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(tmp, snapshotPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            Files.move(tmp, snapshotPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        if (hook != null) {
            hook.checkpoint(PublishPoint.AFTER_SNAPSHOT_BEFORE_MARKER, snapshot.revision());
        }
        Files.writeString(storageDir.resolve(REVISION), Long.toString(snapshot.revision()),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        if (hook != null) {
            hook.checkpoint(PublishPoint.AFTER_MARKER, snapshot.revision());
        }
    }

    public synchronized PointerAnalysisSnapshot loadLatestSnapshot() throws IOException {
        if (!Files.exists(snapshotPath())) {
            return null;
        }
        return mapper.readValue(snapshotPath().toFile(), PointerAnalysisSnapshot.class);
    }

    public synchronized long lastAppliedRevision() throws IOException {
        Path revisionPath = storageDir.resolve(REVISION);
        if (!Files.exists(revisionPath)) {
            PointerAnalysisSnapshot snapshot = loadLatestSnapshot();
            return snapshot == null ? 0L : snapshot.revision();
        }
        return Long.parseLong(Files.readString(revisionPath, StandardCharsets.UTF_8).trim());
    }

    /**
     * Reconciles the on-disk revision marker with the durable snapshot after a
     * crash. The snapshot file is the source of truth: its atomic move is the
     * commit point. A missing/stale marker is refreshed from the snapshot; a
     * marker without a snapshot is removed. Returns the aligned revision (or
     * {@code 0} when no snapshot exists).
     */
    public synchronized long reconcileMarkerWithSnapshot() throws IOException {
        PointerAnalysisSnapshot snapshot =
                Files.exists(snapshotPath()) ? loadLatestSnapshot() : null;
        long snapshotRevision = snapshot == null ? 0L : snapshot.revision();
        Path revisionPath = storageDir.resolve(REVISION);
        long markerRevision = 0L;
        if (Files.exists(revisionPath)) {
            try {
                markerRevision = Long.parseLong(
                        Files.readString(revisionPath, StandardCharsets.UTF_8).trim());
            } catch (NumberFormatException malformed) {
                markerRevision = -1L;
            }
        }
        if (markerRevision != snapshotRevision) {
            if (snapshotRevision == 0L) {
                Files.deleteIfExists(revisionPath);
            } else {
                Files.writeString(revisionPath, Long.toString(snapshotRevision),
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                        StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        }
        return snapshotRevision;
    }

    public synchronized void markApplied(long revision) throws IOException {
        Files.writeString(storageDir.resolve(REVISION), Long.toString(revision),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    public synchronized QueryRecord appendQuery(QueryRecord record) throws IOException {
        long next = sequence.incrementAndGet();
        QueryRecord stored = new QueryRecord(next, record.revision(), record.kind(),
                record.context(), record.left(), record.right(), record.answer());
        Files.writeString(historyPath(), mapper.writeValueAsString(stored) + System.lineSeparator(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        return stored;
    }

    public synchronized List<QueryRecord> queryHistory() throws IOException {
        if (!Files.exists(historyPath())) {
            return List.of();
        }
        List<QueryRecord> records = new ArrayList<>();
        try (Stream<String> lines = Files.lines(historyPath(), StandardCharsets.UTF_8)) {
            lines.filter(line -> !line.isBlank()).forEach(line -> {
                try {
                    records.add(mapper.readValue(line, QueryRecord.class));
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        return records;
    }

    private Path snapshotPath() {
        return storageDir.resolve(SNAPSHOT);
    }

    private Path historyPath() {
        return storageDir.resolve(HISTORY);
    }

    public synchronized void reset() throws IOException {
        Files.deleteIfExists(snapshotPath());
        Files.deleteIfExists(historyPath());
        Files.deleteIfExists(storageDir.resolve(REVISION));
        sequence.set(0);
    }
}
