package com.github.guoyyit.pointeranalysisjava.ast;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * The parsed whole program.
 */
public final class Program {

    private final Map<String, MethodDecl> methods;
    private final List<String> files;
    private final Map<String, String> fileOfMethod;

    public Program(List<String> files, Map<String, MethodDecl> methods) {
        this.files = files.stream().sorted().distinct().toList();
        this.methods = new TreeMap<>(methods);
        Map<String, String> reverseIndex = new TreeMap<>();
        for (MethodDecl method : methods.values()) {
            reverseIndex.put(method.name(), method.file());
        }
        this.fileOfMethod = reverseIndex;
    }

    public Map<String, MethodDecl> methods() {
        return methods;
    }

    public List<String> files() {
        return files;
    }

    public String fileOfMethod(String method) {
        return fileOfMethod.get(method);
    }
}
