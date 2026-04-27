package com.matching.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class KafkaStatus {
    private boolean running;
    private String status;
    private long lastMessageTime;
    private int consumerCount;
}