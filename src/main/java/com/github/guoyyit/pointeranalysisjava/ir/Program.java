package com.github.guoyyit.pointeranalysisjava.ir;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A whole parsed program: classes indexed by name, plus file-level metadata
 * used for incremental updates.
 */
public record Program(SortedMap<String, JClass> classes,
                      SortedMap<String, List<String>> classesByFile,
                      SortedMap<String, String> sourceHashByFile) {

    public Program {
        classes = Collections.unmodifiableSortedMap(new TreeMap<>(classes));
        classesByFile = Collections.unmodifiableSortedMap(new TreeMap<>(classesByFile));
        sourceHashByFile = Collections.unmodifiableSortedMap(new TreeMap<>(sourceHashByFile));
    }

    public JClass classNamed(String name) {
        return classes.get(name);
    }

    public JMethod method(String key) {
        int hash = key.indexOf('#');
        if (hash < 0) {
            return null;
        }
        JClass jClass = classes.get(key.substring(0, hash));
        if (jClass == null) {
            return null;
        }
        for (JMethod method : jClass.methods()) {
            if (method.key().equals(key)) {
                return method;
            }
        }
        return null;
    }

    public Collection<JMethod> allMethods() {
        return classes.values().stream()
                .flatMap(c -> c.methods().stream())
                .collect(Collectors.toCollection(ArrayList::new));
    }
}
