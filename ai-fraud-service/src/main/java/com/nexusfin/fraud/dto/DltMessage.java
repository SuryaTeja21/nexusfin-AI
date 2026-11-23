package com.nexusfin.fraud.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DltMessage {
    private String id;
    private String originalTopic;
    private Integer partition;
    private Long offset;
    private String payload;
    private String exceptionClass;
    private String exceptionMessage;
    private Instant quarantinedAt;
}
