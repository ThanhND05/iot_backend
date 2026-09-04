package edu.ptit.iot.service.impl;

import edu.ptit.iot.dto.response.DeviceResponse;
import edu.ptit.iot.entity.Device;
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

    @Override
    public List<DeviceResponse> getAllDevices() {
        List<Device> devices = deviceRepository.findAll();
        return devices.stream()
                .map(d -> DeviceResponse.builder()
                        .id(d.getId())
                        .name(d.getName())
                        .build()) // Note: fetching last status might require complex query, keep it simple for now
                .collect(Collectors.toList());
    }
}
