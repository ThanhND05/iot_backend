package edu.ptit.iot.service;

import edu.ptit.iot.dto.response.DeviceResponse;
import java.util.List;

public interface DeviceService {
    List<DeviceResponse> getAllDevices();
}
