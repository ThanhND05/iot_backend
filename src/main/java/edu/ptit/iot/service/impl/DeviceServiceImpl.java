package edu.ptit.iot.service.impl;

import edu.ptit.iot.dto.response.DeviceResponse;
import edu.ptit.iot.entity.Action;
import edu.ptit.iot.entity.Device;
import edu.ptit.iot.repository.ActionRepository;
import edu.ptit.iot.repository.DeviceRepository;
import edu.ptit.iot.service.DeviceService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final ActionRepository actionRepository;

    @Override
    public List<DeviceResponse> getAllDevices() {
        List<Device> devices = deviceRepository.findAll();
        return devices.stream()
                .map(d -> {
                    Action latestAction = actionRepository.findFirstByDeviceIdOrderByCreatedAtDesc(d.getId()).orElse(null);
                    return DeviceResponse.builder()
                            .id(d.getId())
                            .name(d.getName())
                            .lastAction(latestAction != null ? latestAction.getAction() : "OFF")
                            .status(latestAction != null ? latestAction.getStatus() : null)
                            .lastUpdatedAt(latestAction != null ? latestAction.getCreatedAt() : null)
                            .build();
                })
                .collect(Collectors.toList());
    }
}
