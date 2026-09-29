package com.github.guoyyit.pointeranalysisjava.service;

import com.github.guoyyit.pointeranalysisjava.analysis.PointerAnalysisSnapshot;
import com.github.guoyyit.pointeranalysisjava.persistence.QueryRecord;

import java.util.List;

/**
 * An immutable handle on one published revision. All reads through one view
 * observe exactly the same snapshot, even while newer revisions are published.
 */
public record RevisionView(long revision, PointerAnalysisSnapshot snapshot,
                           List<QueryRecord> queryHistory) {
}
