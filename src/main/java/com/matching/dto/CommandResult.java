package com.matching.dto;

public class CommandResult {
    private MatchResult matchResult;
    private SyncPayload syncPayload;

    public CommandResult(MatchResult matchResult, SyncPayload syncPayload) {
        this.matchResult = matchResult;
        this.syncPayload = syncPayload;
    }

    public MatchResult getMatchResult() {
        return matchResult;
    }

    public void setMatchResult(MatchResult matchResult) {
        this.matchResult = matchResult;
    }

    public SyncPayload getSyncPayload() {
        return syncPayload;
    }

    public void setSyncPayload(SyncPayload syncPayload) {
        this.syncPayload = syncPayload;
    }
}