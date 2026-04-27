package com.matching.enums;

public enum OrderType {
    LIMIT,         // GTC limit order
    MARKET,        // market order, fill as much as possible, cancel rest
    LIMIT_IOC,     // limit IOC: fill at limit price or better, cancel unfilled
    LIMIT_FOK,     // limit FOK: fill entirely or reject
    LIMIT_POST_ONLY // post only: reject if would immediately match
}