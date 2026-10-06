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
import jakarta.persistence.criteria.Expression;
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
import org.springframework.transaction.annotation.Transactional;

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

    private static final Pattern YYYY_MM_DD_PREFIX = Pattern
            .compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})(?:[ T](.+))?$");

    private static final Pattern DD_MM_YYYY_PREFIX = Pattern
            .compile("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})(?:[ T](.+))?$");

    private static final Pattern YYYY_MM = Pattern.compile("^(\\d{4})[-/](\\d{1,2})$");

    private static final Pattern MM_YYYY = Pattern.compile("^(\\d{1,2})[-/](\\d{4})$");

    private static final Pattern DD_MM = Pattern.compile("^(\\d{1,2})[-/](\\d{1,2})$");

    private static final Pattern YEAR_ONLY = Pattern.compile("^(\\d{4})$");

    private static final Pattern HH_MM_SS_EXACT = Pattern.compile("^(\\d{1,2}):(\\d{2}):(\\d{2})$");

    private static final Pattern HH_MM_S_PREFIX = Pattern.compile("^(\\d{1,2}):(\\d{2}):([0-5])$");

    private static final Pattern HH_MM_PREFIX = Pattern.compile("^(\\d{1,2}):(\\d{2}):?$");

    private static final Pattern HH_M_PREFIX = Pattern.compile("^(\\d{1,2}):([0-5]):?$");

    private static final Pattern HH_COLON = Pattern.compile("^(\\d{1,2}):$");

    private static final Pattern HH_OR_HH_COLON = Pattern.compile("^(\\d{1,2}):?$");

    private static final Pattern DAY_OR_HOUR_ONLY = Pattern.compile("^(\\d{1,2})$");

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
    @Transactional
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

            // 1. yyyy-MM-dd [time]
            matcher = YYYY_MM_DD_PREFIX.matcher(search);
            if (matcher.matches()) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
                return buildDateAndPartialTimePredicate(createdAt, cb, date, matcher.group(4));
            }

            // 2. dd/MM/yyyy [time]
            matcher = DD_MM_YYYY_PREFIX.matcher(search);
            if (matcher.matches()) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(1)));
                return buildDateAndPartialTimePredicate(createdAt, cb, date, matcher.group(4));
            }

            // 3. yyyy-MM
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

            // 4. MM/yyyy
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

            // 5. Standalone partial / full time patterns:
            // "16:50:35", "16:50:3", "16:50", "16:50:", "16:5", "16:"
            Predicate timeOnlyPred = buildPartialTimeOnlyPredicate(createdAt, cb, search);
            if (timeOnlyPred != null) {
                return timeOnlyPred;
            }

            // 6. dd/MM on any year
            matcher = DD_MM.matcher(search);
            if (matcher.matches()) {
                int day = Integer.parseInt(matcher.group(1));
                int month = Integer.parseInt(matcher.group(2));
                LocalDate.of(2024, month, day);

                return cb.and(
                        cb.equal(cb.function("day", Integer.class, createdAt), day),
                        cb.equal(cb.function("month", Integer.class, createdAt), month));
            }

            // 7. 4-digit year only — e.g. "2026"
            matcher = YEAR_ONLY.matcher(search);
            if (matcher.matches()) {
                int year = Integer.parseInt(matcher.group(1));
                return cb.equal(cb.function("year", Integer.class, createdAt), year);
            }

            // 8. 1–2 digit day of month or hour of day — e.g. "16", "29", "5"
            matcher = DAY_OR_HOUR_ONLY.matcher(search);
            if (matcher.matches()) {
                int val = Integer.parseInt(matcher.group(1));
                List<Predicate> options = new ArrayList<>();
                if (val >= 1 && val <= 31) {
                    options.add(cb.equal(cb.function("day", Integer.class, createdAt), val));
                }
                if (val >= 0 && val <= 23) {
                    options.add(cb.equal(cb.function("hour", Integer.class, createdAt), val));
                }
                if (!options.isEmpty()) {
                    return cb.or(options.toArray(new Predicate[0]));
                }
                return null;
            }

            return null;
        } catch (NumberFormatException | DateTimeException ignored) {
            return null;
        }
    }

    private Predicate buildDateAndPartialTimePredicate(
            Path<LocalDateTime> createdAt,
            CriteriaBuilder cb,
            LocalDate date,
            String timePart) {
        if (timePart == null || timePart.isBlank()) {
            return buildDayRange(createdAt, cb, date);
        }
        String t = timePart.trim();

        // 1. HH:mm:ss
        Matcher m = HH_MM_SS_EXACT.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            int s = Integer.parseInt(m.group(3));
            if (h > 23 || min > 59 || s > 59) return null;
            LocalDateTime start = date.atTime(h, min, s);
            return cb.and(
                    cb.greaterThanOrEqualTo(createdAt, start),
                    cb.lessThan(createdAt, start.plusSeconds(1)));
        }

        // 2. HH:mm:s (second prefix e.g. 16:50:3 -> 30..39)
        m = HH_MM_S_PREFIX.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            int sPrefix = Integer.parseInt(m.group(3));
            if (h > 23 || min > 59) return null;
            LocalDateTime start = date.atTime(h, min, sPrefix * 10);
            return cb.and(
                    cb.greaterThanOrEqualTo(createdAt, start),
                    cb.lessThan(createdAt, start.plusSeconds(10)));
        }

        // 3. HH:mm or HH:mm:
        m = HH_MM_PREFIX.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h > 23 || min > 59) return null;
            LocalDateTime start = date.atTime(h, min, 0);
            return cb.and(
                    cb.greaterThanOrEqualTo(createdAt, start),
                    cb.lessThan(createdAt, start.plusMinutes(1)));
        }

        // 4. HH:m or HH:m: (minute prefix e.g. 16:5 -> 50..59)
        m = HH_M_PREFIX.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int mPrefix = Integer.parseInt(m.group(2));
            if (h > 23) return null;
            LocalDateTime start = date.atTime(h, mPrefix * 10, 0);
            return cb.and(
                    cb.greaterThanOrEqualTo(createdAt, start),
                    cb.lessThan(createdAt, start.plusMinutes(10)));
        }

        // 5. HH or HH:
        m = HH_OR_HH_COLON.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            if (h > 23) return null;
            LocalDateTime start = date.atTime(h, 0, 0);
            return cb.and(
                    cb.greaterThanOrEqualTo(createdAt, start),
                    cb.lessThan(createdAt, start.plusHours(1)));
        }

        return null;
    }

    private Predicate buildPartialTimeOnlyPredicate(
            Path<LocalDateTime> createdAt,
            CriteriaBuilder cb,
            String t) {
        // 1. HH:mm:ss
        Matcher m = HH_MM_SS_EXACT.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            int s = Integer.parseInt(m.group(3));
            if (h > 23 || min > 59 || s > 59) return null;
            return cb.and(
                    cb.equal(cb.function("hour", Integer.class, createdAt), h),
                    cb.equal(cb.function("minute", Integer.class, createdAt), min),
                    cb.equal(cb.function("second", Integer.class, createdAt), s));
        }

        // 2. HH:mm:s (second prefix e.g. 16:50:3 -> 30..39)
        m = HH_MM_S_PREFIX.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            int sPrefix = Integer.parseInt(m.group(3));
            if (h > 23 || min > 59) return null;
            Expression<Integer> secExpr = cb.function("second", Integer.class, createdAt);
            return cb.and(
                    cb.equal(cb.function("hour", Integer.class, createdAt), h),
                    cb.equal(cb.function("minute", Integer.class, createdAt), min),
                    cb.greaterThanOrEqualTo(secExpr, sPrefix * 10),
                    cb.lessThanOrEqualTo(secExpr, sPrefix * 10 + 9));
        }

        // 3. HH:mm or HH:mm: (e.g. 16:50 or 16:50:)
        m = HH_MM_PREFIX.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int min = Integer.parseInt(m.group(2));
            if (h > 23 || min > 59) return null;
            return cb.and(
                    cb.equal(cb.function("hour", Integer.class, createdAt), h),
                    cb.equal(cb.function("minute", Integer.class, createdAt), min));
        }

        // 4. HH:m or HH:m: (minute prefix e.g. 16:5 -> 50..59)
        m = HH_M_PREFIX.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            int mPrefix = Integer.parseInt(m.group(2));
            if (h > 23) return null;
            Expression<Integer> minExpr = cb.function("minute", Integer.class, createdAt);
            return cb.and(
                    cb.equal(cb.function("hour", Integer.class, createdAt), h),
                    cb.greaterThanOrEqualTo(minExpr, mPrefix * 10),
                    cb.lessThanOrEqualTo(minExpr, mPrefix * 10 + 9));
        }

        // 5. HH: (hour with colon e.g. 16:)
        m = HH_COLON.matcher(t);
        if (m.matches()) {
            int h = Integer.parseInt(m.group(1));
            if (h > 23) return null;
            return cb.equal(cb.function("hour", Integer.class, createdAt), h);
        }

        return null;
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
