package edu.ptit.iot.service.impl;

import edu.ptit.iot.dto.request.ActionRequest;
import edu.ptit.iot.dto.response.ActionResponse;
import edu.ptit.iot.dto.response.PageResponse;
import edu.ptit.iot.entity.Action;
import edu.ptit.iot.entity.Device;
import edu.ptit.iot.entity.User;
import edu.ptit.iot.exception.ResourceNotFoundException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.integration.mqtt.support.MqttHeaders;
import edu.ptit.iot.repository.ActionRepository;
import edu.ptit.iot.repository.DeviceRepository;
import edu.ptit.iot.repository.UserRepository;
import edu.ptit.iot.service.ActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActionServiceImpl implements ActionService {

    private final ActionRepository actionRepository;
    private final DeviceRepository deviceRepository;
    private final UserRepository userRepository;
    @Qualifier("mqttOutboundChannel")
    private final MessageChannel mqttOutboundChannel;
    private final SimpMessagingTemplate messagingTemplate;

    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(10);

    @Override
    public ActionResponse performAction(Integer deviceId, ActionRequest request) {
        Device device = deviceRepository.findById(deviceId)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found"));
        User user = userRepository.findById(request.getUserId())
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Action action = Action.builder()
                .device(device)
                .user(user)
                .action(request.getAction())
                .status("PENDING")
                .build();
        
        Action savedAction = actionRepository.save(action);

        // Publish to MQTT
        String topic = "iot/devices/" + deviceId + "/action";
        String message = String.format("{\"actionId\": %d, \"deviceId\": %d, \"action\": \"%s\"}", 
                savedAction.getId(), deviceId, request.getAction());
        Message<String> mqttMessage = MessageBuilder.withPayload(message)
                .setHeader(MqttHeaders.TOPIC, topic)
                .build();
        mqttOutboundChannel.send(mqttMessage);

        // Schedule timeout check
        scheduler.schedule(() -> checkTimeout(savedAction.getId(), deviceId), 5, TimeUnit.SECONDS);

        return mapToActionResponse(savedAction);
    }

    private void checkTimeout(Integer actionId, Integer deviceId) {
        actionRepository.findById(actionId).ifPresent(action -> {
            if ("PENDING".equals(action.getStatus())) {
                action.setStatus("FAILED");
                actionRepository.save(action);

                // Notify WebSocket
                messagingTemplate.convertAndSend("/topic/devices/" + deviceId + "/status", mapToActionResponse(action));
            }
        });
    }

    @Override
    public void updateActionStatus(Integer actionId, Integer deviceId, String status) {
        actionRepository.findById(actionId).ifPresent(action -> {
            if ("PENDING".equals(action.getStatus())) {
                action.setStatus(status);
                actionRepository.save(action);

                // Notify WebSocket
                messagingTemplate.convertAndSend("/topic/devices/" + deviceId + "/status", mapToActionResponse(action));
            }
        });
    }

    @Override
    public PageResponse<ActionResponse> searchActions(int page, int size, String device, String time) {
        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        
        LocalDateTime startTime = null;
        LocalDateTime endTime = null;
        if (time != null && !time.isEmpty()) {
            try {
                LocalDate date = LocalDate.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd"));
                startTime = date.atStartOfDay();
                endTime = date.plusDays(1).atStartOfDay().minusNanos(1);
            } catch (DateTimeParseException e) {
                // Ignore parsing errors for simple search
            }
        }

        Page<Action> dataPage = actionRepository.searchActions(device, startTime, endTime, pageable);
        
        List<ActionResponse> content = dataPage.getContent().stream()
                .map(this::mapToActionResponse)
                .collect(Collectors.toList());

        return PageResponse.<ActionResponse>builder()
                .content(content)
                .page(dataPage.getNumber())
                .size(dataPage.getSize())
                .totalElements(dataPage.getTotalElements())
                .totalPages(dataPage.getTotalPages())
                .build();
    }

    private ActionResponse mapToActionResponse(Action action) {
        return ActionResponse.builder()
                .id(action.getId())
                .deviceId(action.getDevice().getId())
                .deviceName(action.getDevice().getName())
                .userId(action.getUser().getId())
                .action(action.getAction())
                .status(action.getStatus())
                .createdAt(action.getCreatedAt())
                .build();
    }
}
