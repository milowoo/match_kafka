package com.matching.disruptor;

import com.matching.dto.MatchResult;
import java.util.Map;

/**
 * Splits a MatchResult by accountId and serializes each to Protobuf bytes. Returns Map.
 */
@FunctionalInterface
public interface ResultSplitter {
    Map<String, byte[]> splitAndSerialize(String symbolId, MatchResult matchResult);
}