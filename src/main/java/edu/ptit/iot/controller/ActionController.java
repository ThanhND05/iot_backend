package edu.ptit.iot.controller;

import edu.ptit.iot.dto.response.ActionResponse;
import edu.ptit.iot.dto.response.ApiResponse;
import edu.ptit.iot.dto.response.PageResponse;
import edu.ptit.iot.service.ActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/actions")
@RequiredArgsConstructor
@CrossOrigin("*")
public class ActionController {

    private final ActionService actionService;

    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<ActionResponse>>> searchActions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size,
            @RequestParam(required = false) String device,
            @RequestParam(required = false) String time) {
        
        PageResponse<ActionResponse> data = actionService.searchActions(page, size, device, time);
        return ResponseEntity.ok(ApiResponse.<PageResponse<ActionResponse>>builder()
                .success(true)
                .message("Actions fetched successfully!")
                .data(data)
                .build());
    }
}
