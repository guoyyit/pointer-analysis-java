package com.github.guoyyit.pointeranalysisjava.model;

import java.util.ArrayList;
import java.util.List;

/**
 * A calling context: a k-limited call string of call-site identifiers
 * (outermost first). The empty context is {@link #ROOT}.
 */
public record Context(List<String> callSites) implements Comparable<Context> {

    public static final Context ROOT = new Context(List.of());

    public Context {
        callSites = List.copyOf(callSites);
    }

    public static Context of(List<String> callSites) {
        return new Context(callSites);
    }

    public Context push(String callSite, int k) {
        List<String> next = new ArrayList<>(callSites);
        next.add(callSite);
        while (k >= 0 && next.size() > k) {
            next.remove(0);
        }
        return new Context(next);
    }

    public boolean isRoot() {
        return callSites.isEmpty();
    }

    @Override
    public int compareTo(Context other) {
        int size = Integer.compare(callSites.size(), other.callSites.size());
        if (size != 0) {
            return size;
        }
        for (int i = 0; i < callSites.size(); i++) {
            int cmp = callSites.get(i).compareTo(other.callSites.get(i));
            if (cmp != 0) {
                return cmp;
            }
        }
        return 0;
    }

    @Override
    public String toString() {
        return callSites.isEmpty() ? "<root>" : String.join("/", callSites);
    }
}
