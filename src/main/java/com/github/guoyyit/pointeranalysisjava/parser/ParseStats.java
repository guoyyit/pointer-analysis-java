package com.github.guoyyit.pointeranalysisjava.parser;

import java.util.SortedSet;

/**
 * Observable execution details of one source-parsing run.
 *
 * @param parallelism   configured worker count
 * @param files         number of parsed files
 * @param workerThreads distinct worker threads that actually parsed a file
 * @param elapsedMillis wall-clock duration of the parse
 */
public record ParseStats(int parallelism, int files, SortedSet<String> workerThreads,
                         long elapsedMillis) {
}
