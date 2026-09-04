package edu.ptit.iot.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MqttSensorMessage {
    private Double temperature;
    private Double humidity;
    private Double light;
    private LocalDateTime timestamp;
}
