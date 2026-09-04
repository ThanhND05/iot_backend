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
public class DataSensorResponse {
    private Integer id;
    private Integer sensorId;
    private String sensorType;
    private String value;
    private LocalDateTime createdAt;
}
