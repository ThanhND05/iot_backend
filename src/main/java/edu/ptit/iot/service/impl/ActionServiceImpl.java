package edu.ptit.iot.service.impl;

import edu.ptit.iot.dto.request.ActionRequest;
import edu.ptit.iot.dto.response.ActionResponse;
import edu.ptit.iot.dto.response.PageResponse;
import edu.ptit.iot.entity.Action;
import edu.ptit.iot.entity.Device;
import edu.ptit.iot.entity.User;
import edu.ptit.iot.exception.ResourceNotFoundException;
import edu.ptit.iot.repository.ActionRepository;
import edu.ptit.iot.repository.DeviceRepository;
import edu.ptit.iot.repository.UserRepository;
import edu.ptit.iot.service.ActionService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ActionServiceImpl implements ActionService {

    private static final int MAX_PAGE_SIZE = 100;

    private static final Pattern YYYY_MM_DD = Pattern.compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})$");

    private static final Pattern DD_MM_YYYY = Pattern.compile("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})$");

    private static final Pattern YYYY_MM = Pattern.compile("^(\\d{4})[-/](\\d{1,2})$");

    private static final Pattern MM_YYYY = Pattern.compile("^(\\d{1,2})[-/](\\d{4})$");

    private static final Pattern DD_MM = Pattern.compile("^(\\d{1,2})[-/](\\d{1,2})$");

    private static final Pattern HH_MM_SS = Pattern.compile("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$");

    private static final Pattern YYYY_MM_DD_TIME = Pattern
            .compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?$");

    private static final Pattern DD_MM_YYYY_TIME = Pattern
            .compile("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})[ T](\\d{1,2}):(\\d{2})(?::(\\d{2}))?$");

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

        String topic = "iot/devices/" + deviceId + "/action";

        String message = String.format(
                "{\"actionId\": %d, \"deviceId\": %d, \"action\": \"%s\"}",
                savedAction.getId(),
                deviceId,
                request.getAction());

        Message<String> mqttMessage = MessageBuilder
                .withPayload(message)
                .setHeader(MqttHeaders.TOPIC, topic)
                .build();

        mqttOutboundChannel.send(mqttMessage);

        // Wait 15 seconds for the device to respond before marking as FAILED.
        // ESP8266 can be slow to respond via MQTT, 5s was too short.
        scheduler.schedule(
                () -> checkTimeout(savedAction.getId(), deviceId),
                15,
                TimeUnit.SECONDS);

        return mapToActionResponse(savedAction);
    }

    private void checkTimeout(Integer actionId, Integer deviceId) {
        actionRepository.findById(actionId).ifPresent(action -> {
            if ("PENDING".equals(action.getStatus())) {
                action.setStatus("FAILED");
                actionRepository.save(action);

                messagingTemplate.convertAndSend(
                        "/topic/devices/" + deviceId + "/status",
                        mapToActionResponse(action));
            }
        });
    }

    @Override
    public void updateActionStatus(
            Integer actionId,
            Integer deviceId,
            String status) {
        actionRepository.findById(actionId).ifPresent(action -> {
            if ("PENDING".equals(action.getStatus())) {
                action.setStatus(status);
                actionRepository.save(action);

                messagingTemplate.convertAndSend(
                        "/topic/devices/" + deviceId + "/status",
                        mapToActionResponse(action));
            }
        });
    }

    @Override
    public PageResponse<ActionResponse> searchActions(
            int page,
            int size,
            String device,
            String action,
            String status,
            String time) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        String safeDevice = normalizeNullable(device);
        String safeAction = normalizeNullable(action);
        String safeStatus = normalizeNullable(status);
        String safeTime = normalizeNullable(time);

        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by("createdAt").descending());

        Specification<Action> specification = buildSearchSpecification(
                safeDevice,
                safeAction,
                safeStatus,
                safeTime);

        Page<Action> dataPage = actionRepository.findAll(specification, pageable);

        List<ActionResponse> content = dataPage.getContent()
                .stream()
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

    private String normalizeNullable(String value) {
        if (value == null || value.isBlank() || "ALL".equalsIgnoreCase(value)) {
            return null;
        }

        return value.trim();
    }

    private Specification<Action> buildSearchSpecification(
            String device,
            String action,
            String status,
            String time) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (device != null) {
                predicates.add(
                        cb.equal(
                                root.get("device").get("name"),
                                device));
            }

            if (action != null) {
                predicates.add(
                        cb.equal(
                                cb.upper(root.get("action")),
                                action.toUpperCase(Locale.ROOT)));
            }

            if (status != null) {
                predicates.add(
                        cb.equal(
                                cb.upper(root.get("status")),
                                status.toUpperCase(Locale.ROOT)));
            }

            if (time != null) {
                Predicate timePredicate = buildTimePredicate(
                        root.get("createdAt"),
                        cb,
                        time);

                // Nếu chuỗi thời gian không hợp lệ thì trả 0 kết quả
                // thay vì vô tình trả toàn bộ lịch sử.
                predicates.add(
                        timePredicate != null
                                ? timePredicate
                                : cb.disjunction());
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private Predicate buildTimePredicate(
            Path<LocalDateTime> createdAt,
            CriteriaBuilder cb,
            String rawSearch) {
        String search = rawSearch.trim();

        try {
            Matcher matcher;

            // yyyy-MM-dd HH:mm[:ss]
            matcher = YYYY_MM_DD_TIME.matcher(search);
            if (matcher.matches()) {
                return buildDateTimeRange(
                        createdAt,
                        cb,
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(4)),
                        Integer.parseInt(matcher.group(5)),
                        matcher.group(6) == null
                                ? null
                                : Integer.parseInt(matcher.group(6)));
            }

            // dd/MM/yyyy HH:mm[:ss]
            matcher = DD_MM_YYYY_TIME.matcher(search);
            if (matcher.matches()) {
                return buildDateTimeRange(
                        createdAt,
                        cb,
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(4)),
                        Integer.parseInt(matcher.group(5)),
                        matcher.group(6) == null
                                ? null
                                : Integer.parseInt(matcher.group(6)));
            }

            // yyyy-MM-dd
            matcher = YYYY_MM_DD.matcher(search);
            if (matcher.matches()) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));

                return buildDayRange(createdAt, cb, date);
            }

            // dd/MM/yyyy hoặc dd-MM-yyyy
            matcher = DD_MM_YYYY.matcher(search);
            if (matcher.matches()) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(1)));

                return buildDayRange(createdAt, cb, date);
            }

            // yyyy-MM
            matcher = YYYY_MM.matcher(search);
            if (matcher.matches()) {
                YearMonth yearMonth = YearMonth.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)));

                LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
                LocalDateTime end = yearMonth.plusMonths(1).atDay(1).atStartOfDay();

                return cb.and(
                        cb.greaterThanOrEqualTo(createdAt, start),
                        cb.lessThan(createdAt, end));
            }

            // MM/yyyy
            matcher = MM_YYYY.matcher(search);
            if (matcher.matches()) {
                YearMonth yearMonth = YearMonth.of(
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(1)));

                LocalDateTime start = yearMonth.atDay(1).atStartOfDay();
                LocalDateTime end = yearMonth.plusMonths(1).atDay(1).atStartOfDay();

                return cb.and(
                        cb.greaterThanOrEqualTo(createdAt, start),
                        cb.lessThan(createdAt, end));
            }

            // HH:mm hoặc HH:mm:ss trên mọi ngày.
            matcher = HH_MM_SS.matcher(search);
            if (matcher.matches()) {
                int hour = Integer.parseInt(matcher.group(1));
                int minute = Integer.parseInt(matcher.group(2));

                if (hour > 23 || minute > 59) {
                    return null;
                }

                List<Predicate> timeParts = new ArrayList<>();

                timeParts.add(
                        cb.equal(
                                cb.function("hour", Integer.class, createdAt),
                                hour));

                timeParts.add(
                        cb.equal(
                                cb.function("minute", Integer.class, createdAt),
                                minute));

                if (matcher.group(3) != null) {
                    int second = Integer.parseInt(matcher.group(3));

                    if (second > 59) {
                        return null;
                    }

                    timeParts.add(
                            cb.equal(
                                    cb.function("second", Integer.class, createdAt),
                                    second));
                }

                return cb.and(timeParts.toArray(new Predicate[0]));
            }

            // dd/MM trên mọi năm.
            matcher = DD_MM.matcher(search);
            if (matcher.matches()) {
                int day = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));

                LocalDate.of(2024, month, day);

                return cb.and(
                        cb.equal(
                                cb.function("day", Integer.class, createdAt),
                                day),
                        cb.equal(
                                cb.function("month", Integer.class, createdAt),
                                month));
            }

            return null;
        } catch (NumberFormatException | DateTimeException ignored) {
            return null;
        }
    }

    private Predicate buildDayRange(
            Path<LocalDateTime> createdAt,
            CriteriaBuilder cb,
            LocalDate date) {
        LocalDateTime start = date.atStartOfDay();
        LocalDateTime end = date.plusDays(1).atStartOfDay();

        return cb.and(
                cb.greaterThanOrEqualTo(createdAt, start),
                cb.lessThan(createdAt, end));
    }

    private Predicate buildDateTimeRange(
            Path<LocalDateTime> createdAt,
            CriteriaBuilder cb,
            int year,
            int month,
            int day,
            int hour,
            int minute,
            Integer second) {
        LocalDateTime start = LocalDateTime.of(
                year,
                month,
                day,
                hour,
                minute,
                second == null ? 0 : second);

        LocalDateTime end = second == null
                ? start.plusMinutes(1)
                : start.plusSeconds(1);

        return cb.and(
                cb.greaterThanOrEqualTo(createdAt, start),
                cb.lessThan(createdAt, end));
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
