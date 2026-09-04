package edu.ptit.iot.service.impl;

import edu.ptit.iot.dto.response.DataSensorResponse;
import edu.ptit.iot.dto.response.LatestDataSensorResponse;
import edu.ptit.iot.dto.response.PageResponse;
import edu.ptit.iot.entity.DataSensor;
import edu.ptit.iot.repository.DataSensorRepository;
import edu.ptit.iot.service.DataSensorService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DataSensorServiceImpl implements DataSensorService {

    private final DataSensorRepository dataSensorRepository;

    @Override
    public LatestDataSensorResponse getLatestData() {
        List<DataSensor> temps = dataSensorRepository.findTopNBySensorNameOrderByCreatedAtDesc("temperature", 1);
        List<DataSensor> hums = dataSensorRepository.findTopNBySensorNameOrderByCreatedAtDesc("humidity", 1);
        List<DataSensor> lights = dataSensorRepository.findTopNBySensorNameOrderByCreatedAtDesc("light", 1);

        LatestDataSensorResponse response = new LatestDataSensorResponse();
        if (!temps.isEmpty()) {
            response.setTemperature(Double.parseDouble(temps.get(0).getValue()));
            response.setTimestamp(temps.get(0).getCreatedAt());
        }
        if (!hums.isEmpty()) {
            response.setHumidity(Double.parseDouble(hums.get(0).getValue()));
            if (response.getTimestamp() == null) response.setTimestamp(hums.get(0).getCreatedAt());
        }
        if (!lights.isEmpty()) {
            response.setLight(Double.parseDouble(lights.get(0).getValue()));
            if (response.getTimestamp() == null) response.setTimestamp(lights.get(0).getCreatedAt());
        }
        return response;
    }

    @Override
    public List<LatestDataSensorResponse> getChartData() {
        List<DataSensor> temps = dataSensorRepository.findTopNBySensorNameOrderByCreatedAtDesc("temperature", 10);
        List<DataSensor> hums = dataSensorRepository.findTopNBySensorNameOrderByCreatedAtDesc("humidity", 10);
        List<DataSensor> lights = dataSensorRepository.findTopNBySensorNameOrderByCreatedAtDesc("light", 10);

        List<LatestDataSensorResponse> responses = new ArrayList<>();
        int maxSize = Math.max(Math.max(temps.size(), hums.size()), lights.size());

        for (int i = 0; i < maxSize; i++) {
            LatestDataSensorResponse response = new LatestDataSensorResponse();
            if (i < temps.size()) {
                response.setTemperature(Double.parseDouble(temps.get(i).getValue()));
                response.setTimestamp(temps.get(i).getCreatedAt());
            }
            if (i < hums.size()) {
                response.setHumidity(Double.parseDouble(hums.get(i).getValue()));
                if (response.getTimestamp() == null) response.setTimestamp(hums.get(i).getCreatedAt());
            }
            if (i < lights.size()) {
                response.setLight(Double.parseDouble(lights.get(i).getValue()));
                if (response.getTimestamp() == null) response.setTimestamp(lights.get(i).getCreatedAt());
            }
            responses.add(response);
        }
        Collections.reverse(responses);
        return responses;
    }

    @Override
    public PageResponse<DataSensorResponse> searchDataSensors(int page, int size, String type, String time) {
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
        if (type == null || type.isEmpty()) {
            type = "All";
        }

        Page<DataSensor> dataPage = dataSensorRepository.searchDataSensors(type, startTime, endTime, pageable);
        
        List<DataSensorResponse> content = dataPage.getContent().stream()
                .map(d -> DataSensorResponse.builder()
                        .id(d.getId())
                        .sensorId(d.getSensor().getId())
                        .sensorType(d.getSensor().getName())
                        .value(d.getValue())
                        .createdAt(d.getCreatedAt())
                        .build())
                .collect(Collectors.toList());

        return PageResponse.<DataSensorResponse>builder()
                .content(content)
                .page(dataPage.getNumber())
                .size(dataPage.getSize())
                .totalElements(dataPage.getTotalElements())
                .totalPages(dataPage.getTotalPages())
                .build();
    }
}
