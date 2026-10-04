package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.ir.Program;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeSet;
import java.util.SortedSet;

/**
 * File-level difference between two programs.
 */
public record ChangeSet(SortedSet<String> addedFiles,
                        SortedSet<String> changedFiles,
                        SortedSet<String> deletedFiles) {

    public boolean isEmpty() {
        return addedFiles.isEmpty() && changedFiles.isEmpty() && deletedFiles.isEmpty();
    }

    public static ChangeSet diff(Program previous, Program next) {
        SortedSet<String> added = new TreeSet<>();
        SortedSet<String> changed = new TreeSet<>();
        SortedSet<String> deleted = new TreeSet<>();

        for (String path : next.sourceHashByFile().keySet()) {
            String oldHash = previous.sourceHashByFile().get(path);
            if (oldHash == null) {
                added.add(path);
            } else if (!oldHash.equals(next.sourceHashByFile().get(path))) {
                changed.add(path);
            }
        }
        for (String path : previous.sourceHashByFile().keySet()) {
            if (!next.sourceHashByFile().containsKey(path)) {
                deleted.add(path);
            }
        }
        return new ChangeSet(added, changed, deleted);
    }

    /**
     * Deterministic identity of this change set applied on top of
     * {@code baseRevision}. Two updates with the same fingerprint are the same
     * logical change and must not be applied twice.
     */
    public String fingerprint(long baseRevision, Program next) {
        StringBuilder sb = new StringBuilder();
        sb.append(baseRevision).append('\n');
        appendFiles(sb, "added", addedFiles, next);
        appendFiles(sb, "changed", changedFiles, next);
        sb.append("deleted:");
        for (String path : deletedFiles) {
            sb.append(path).append(';');
        }
        sb.append('\n');
        return sha256(sb.toString());
    }

    private static void appendFiles(StringBuilder sb, String label, SortedSet<String> files,
                                    Program next) {
        sb.append(label).append(':');
        for (String path : files) {
            sb.append(path).append('=').append(next.sourceHashByFile().get(path)).append(';');
        }
        sb.append('\n');
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
