package com.github.guoyyit.pointeranalysisjava.analysis;

import com.github.guoyyit.pointeranalysisjava.ir.Program;

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
}
