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
public class DeviceResponse {
    private Integer id;
    private String name;
    private String lastAction;
    private String status;
    private LocalDateTime lastUpdatedAt;
}
