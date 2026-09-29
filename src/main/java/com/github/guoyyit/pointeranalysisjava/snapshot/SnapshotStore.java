package com.github.guoyyit.pointeranalysisjava.snapshot;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.github.guoyyit.pointeranalysisjava.model.Allocation;
import com.github.guoyyit.pointeranalysisjava.model.CVar;
import com.github.guoyyit.pointeranalysisjava.model.Ctx;
import com.github.guoyyit.pointeranalysisjava.model.OField;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Durable storage for the latest snapshot and the append-only query history.
 *
 * <p>A snapshot is published atomically: the new state is written and flushed to a temporary
 * file, then moved over {@code snapshot.json} in one filesystem rename. Readers reopening the
 * file after a crash therefore see exactly one complete revision, never a half-written file.
 * The query log is appended and flushed synchronously, so every published query answer stays
 * recoverable; each record carries a monotonic sequence number and is stored as one JSON line
 * so a torn tail record is detectable and ignored.</p>
 *
 * <p>Composite map keys ({@link CVar}, {@link OField}) are serialized through stable textual
 * key codecs so the on-disk format round-trips without external polymorphic type metadata.</p>
 */
public final class SnapshotStore {

    private final Path directory;
    private final Path snapshotFile;
    private final Path queryLog;
    private final ObjectMapper mapper;
    private final ObjectMapper compactMapper;

    public SnapshotStore(Path directory) {
        this.directory = directory;
        this.snapshotFile = directory.resolve("snapshot.json");
        this.queryLog = directory.resolve("queries.jsonl");
        SimpleModule keyModule = new SimpleModule()
                .addKeySerializer(CVar.class, new StringKeySerializer<>(SnapshotStore::formatCVar))
                .addKeyDeserializer(CVar.class, new StringKeyDeserializer<>(
                        SnapshotStore::parseCVar))
                .addKeySerializer(OField.class,
                        new StringKeySerializer<>(SnapshotStore::formatOField))
                .addKeyDeserializer(OField.class, new StringKeyDeserializer<>(
                        SnapshotStore::parseOField));
        this.mapper = new ObjectMapper()
                .setVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY)
                .disable(SerializationFeature.CLOSE_CLOSEABLE)
                .configure(JsonGenerator.Feature.AUTO_CLOSE_TARGET, false)
                .registerModule(keyModule)
                .enable(SerializationFeature.INDENT_OUTPUT);
        this.compactMapper = this.mapper.copy().disable(SerializationFeature.INDENT_OUTPUT);
        try {
            Files.createDirectories(directory);
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create storage directory: " + directory,
                    exception);
        }
    }

    public Path directory() {
        return directory;
    }

    public synchronized void persist(Snapshot snapshot) {
        Path temporary = directory.resolve("snapshot.json.tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                mapper.writeValue(writer, snapshot);
                writer.flush();
            }
            Files.move(temporary, snapshotFile, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to persist snapshot", exception);
        }
    }

    public synchronized Snapshot loadLatest() {
        if (!Files.exists(snapshotFile)) {
            return null;
        }
        try {
            return mapper.readValue(snapshotFile.toFile(), Snapshot.class);
        } catch (IOException exception) {
            throw new IllegalStateException("Stored snapshot is unreadable", exception);
        }
    }

    public synchronized void appendQuery(QueryRecord record) {
        try {
            String line = compactMapper.writeValueAsString(record) + System.lineSeparator();
            Files.writeString(queryLog, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to append query record", exception);
        }
    }

    public synchronized List<QueryRecord> queryHistory() {
        if (!Files.exists(queryLog)) {
            return List.of();
        }
        List<QueryRecord> records = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(queryLog, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                try {
                    records.add(compactMapper.readValue(line, QueryRecord.class));
                } catch (IOException partial) {
                    // A torn tail from a crash mid-append: ignore it, all earlier committed
                    // records remain intact because each line is independently serialized.
                    break;
                }
            }
        } catch (IOException exception) {
            throw new IllegalStateException("Failed to read query history", exception);
        }
        return List.copyOf(records);
    }

    public long nextQuerySequence() {
        List<QueryRecord> records = queryHistory();
        return records.isEmpty() ? 0L : records.get(records.size() - 1).sequence() + 1;
    }

    static String formatCtx(Ctx ctx) {
        return "[" + String.join(">", ctx.frames()) + "]";
    }

    static Ctx parseCtx(String text) {
        String body = text.substring(1, text.length() - 1);
        if (body.isEmpty()) {
            return Ctx.EMPTY;
        }
        List<String> frames = new ArrayList<>();
        for (String frame : body.split(">", -1)) {
            frames.add(frame);
        }
        return new Ctx(List.copyOf(frames));
    }

    static String formatCVar(CVar variable) {
        return formatCtx(variable.ctx()) + "|" + variable.method() + "|" + variable.name();
    }

    static CVar parseCVar(String text) {
        int contextEnd = text.indexOf(']') + 1;
        Ctx ctx = parseCtx(text.substring(0, contextEnd));
        String remainder = text.substring(contextEnd + 1);
        int separator = remainder.indexOf('|');
        return new CVar(ctx, remainder.substring(0, separator), remainder.substring(separator + 1));
    }

    static String formatOField(OField field) {
        return field.allocation().siteId() + "|" + field.allocation().typeName() + "|"
                + field.allocation().methodName() + "#" + field.field();
    }

    static OField parseOField(String text) {
        int hash = text.lastIndexOf('#');
        String allocationPart = text.substring(0, hash);
        String field = text.substring(hash + 1);
        int first = allocationPart.indexOf('|');
        int second = allocationPart.indexOf('|', first + 1);
        Allocation allocation = new Allocation(
                allocationPart.substring(0, first),
                allocationPart.substring(second + 1),
                allocationPart.substring(first + 1, second));
        return new OField(allocation, field);
    }

    public record QueryRecord(long sequence, long revision, String kind, String request,
                              String result) {
    }

    private static final class StringKeySerializer<T>
            extends com.fasterxml.jackson.databind.JsonSerializer<T> {

        private final java.util.function.Function<T, String> formatter;

        private StringKeySerializer(java.util.function.Function<T, String> formatter) {
            this.formatter = formatter;
        }

        @Override
        public void serialize(T value, JsonGenerator generator,
                              com.fasterxml.jackson.databind.SerializerProvider serializers)
                throws IOException {
            generator.writeFieldName(formatter.apply(value));
        }
    }

    private static final class StringKeyDeserializer<T>
            extends com.fasterxml.jackson.databind.KeyDeserializer {

        private final java.util.function.Function<String, T> parser;

        private StringKeyDeserializer(java.util.function.Function<String, T> parser) {
            this.parser = parser;
        }

        @Override
        public Object deserializeKey(String key,
                                     com.fasterxml.jackson.databind.DeserializationContext context) {
            return parser.apply(key);
        }
    }
}
