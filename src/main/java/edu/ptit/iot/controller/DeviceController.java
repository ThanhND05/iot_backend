package edu.ptit.iot.controller;

import edu.ptit.iot.dto.request.ActionRequest;
import edu.ptit.iot.dto.response.ActionResponse;
import edu.ptit.iot.dto.response.ApiResponse;
import edu.ptit.iot.dto.response.DeviceResponse;
import edu.ptit.iot.service.ActionService;
import edu.ptit.iot.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
@CrossOrigin("*")
public class DeviceController {

    private final DeviceService deviceService;
    private final ActionService actionService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> getAllDevices() {
        List<DeviceResponse> data = deviceService.getAllDevices();
        return ResponseEntity.ok(ApiResponse.<List<DeviceResponse>>builder()
                .success(true)
                .message("Devices fetched successfully!")
                .data(data)
                .build());
    }

    @PostMapping("/{deviceId}/actions")
    public ResponseEntity<ApiResponse<ActionResponse>> performAction(
            @PathVariable Integer deviceId,
            @RequestBody ActionRequest request) {
        
        ActionResponse data = actionService.performAction(deviceId, request);
        return ResponseEntity.ok(ApiResponse.<ActionResponse>builder()
                .success(true)
                .message("Action accepted")
                .data(data)
                .build());
    }
}
