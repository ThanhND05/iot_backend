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
public class LatestDataSensorResponse {
    private Double temperature;
    private Double humidity;
    private Double light;
    private LocalDateTime timestamp;
}
