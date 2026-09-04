package edu.ptit.iot.controller;

import edu.ptit.iot.dto.response.ApiResponse;
import edu.ptit.iot.dto.response.DataSensorResponse;
import edu.ptit.iot.dto.response.LatestDataSensorResponse;
import edu.ptit.iot.dto.response.PageResponse;
import edu.ptit.iot.service.DataSensorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasensors")
@RequiredArgsConstructor
@CrossOrigin("*")
public class DataSensorController {

    private final DataSensorService dataSensorService;

    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<LatestDataSensorResponse>> getLatestData() {
        LatestDataSensorResponse data = dataSensorService.getLatestData();
        return ResponseEntity.ok(ApiResponse.<LatestDataSensorResponse>builder()
                .success(true)
                .message("Sensors value fetched successfully!")
                .data(data)
                .build());
    }

    @GetMapping("/chart/latest")
    public ResponseEntity<ApiResponse<List<LatestDataSensorResponse>>> getChartData() {
        List<LatestDataSensorResponse> data = dataSensorService.getChartData();
        return ResponseEntity.ok(ApiResponse.<List<LatestDataSensorResponse>>builder()
                .success(true)
                .message("Chart data fetched successfully!")
                .data(data)
                .build());
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<DataSensorResponse>>> searchDataSensors(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false, defaultValue = "All") String type,
            @RequestParam(required = false) String time) {
        
        PageResponse<DataSensorResponse> data = dataSensorService.searchDataSensors(page, size, type, time);
        return ResponseEntity.ok(ApiResponse.<PageResponse<DataSensorResponse>>builder()
                .success(true)
                .message("Datasensors fetched successfully!")
                .data(data)
                .build());
    }
}
