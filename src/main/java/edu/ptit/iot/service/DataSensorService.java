package edu.ptit.iot.service;

import edu.ptit.iot.dto.response.DataSensorResponse;
import edu.ptit.iot.dto.response.LatestDataSensorResponse;
import edu.ptit.iot.dto.response.PageResponse;

import java.time.LocalDateTime;
import java.util.List;

public interface DataSensorService {
    LatestDataSensorResponse getLatestData();
    List<LatestDataSensorResponse> getChartData();
    PageResponse<DataSensorResponse> searchDataSensors(int page, int size, String type, String time);
}
