package edu.ptit.iot.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.ptit.iot.dto.response.LatestDataSensorResponse;
import edu.ptit.iot.entity.DataSensor;
import edu.ptit.iot.entity.Sensor;
import edu.ptit.iot.repository.DataSensorRepository;
import edu.ptit.iot.repository.SensorRepository;
import edu.ptit.iot.service.ActionService;
import lombok.RequiredArgsConstructor;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
public class MqttMessageHandler {

    private final ObjectMapper objectMapper;
    private final SensorRepository sensorRepository;
    private final DataSensorRepository dataSensorRepository;
    private final ActionService actionService;
    private final SimpMessagingTemplate messagingTemplate;

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleMessage(Message<?> message) {
        String topic = message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC).toString();
        String payload = message.getPayload().toString();

        try {
            JsonNode jsonNode = objectMapper.readTree(payload);

            if ("iot/sensors/data".equals(topic)) {
                handleSensorData(jsonNode);
            } else if (topic.startsWith("iot/devices/") && topic.endsWith("/action/status")) {
                handleActionStatus(jsonNode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleSensorData(JsonNode jsonNode) {
        double temperature = jsonNode.has("temperature") ? jsonNode.get("temperature").asDouble() : 0;
        double humidity = jsonNode.has("humidity") ? jsonNode.get("humidity").asDouble() : 0;
        double light = jsonNode.has("light") ? jsonNode.get("light").asDouble() : 0;
        
        String timestampStr = jsonNode.has("timestamp") ? jsonNode.get("timestamp").asText() : null;
        LocalDateTime timestamp = timestampStr != null 
                ? LocalDateTime.parse(timestampStr, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
                : LocalDateTime.now();

        saveSensorData("temperature", String.valueOf(temperature), timestamp);
        saveSensorData("humidity", String.valueOf(humidity), timestamp);
        saveSensorData("light", String.valueOf(light), timestamp);

        LatestDataSensorResponse response = LatestDataSensorResponse.builder()
                .temperature(temperature)
                .humidity(humidity)
                .light(light)
                .timestamp(timestamp)
                .build();
                
        messagingTemplate.convertAndSend("/topic/sensors", response);
    }

    private void saveSensorData(String sensorName, String value, LocalDateTime timestamp) {
        Sensor sensor = sensorRepository.findByName(sensorName).orElseGet(() -> {
            Sensor newSensor = Sensor.builder().name(sensorName).build();
            return sensorRepository.save(newSensor);
        });

        DataSensor dataSensor = DataSensor.builder()
                .sensor(sensor)
                .value(value)
                .createdAt(timestamp)
                .build();
        dataSensorRepository.save(dataSensor);
    }

    private void handleActionStatus(JsonNode jsonNode) {
        if (jsonNode.has("actionId") && jsonNode.has("deviceId") && jsonNode.has("status")) {
            Integer actionId = jsonNode.get("actionId").asInt();
            Integer deviceId = jsonNode.get("deviceId").asInt();
            String status = jsonNode.get("status").asText();

            actionService.updateActionStatus(actionId, deviceId, status);
        }
    }
}
