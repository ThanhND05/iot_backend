package edu.ptit.iot.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ActionResponse {
    private Integer id;
    private Integer deviceId;
    private String deviceName;
    private Integer userId;
    private String action;
    private String status;
    private LocalDateTime createdAt;
}
