package edu.ptit.iot.service.impl;

import edu.ptit.iot.dto.response.DataSensorResponse;
import edu.ptit.iot.dto.response.LatestDataSensorResponse;
import edu.ptit.iot.dto.response.PageResponse;
import edu.ptit.iot.entity.DataSensor;
import edu.ptit.iot.repository.DataSensorRepository;
import edu.ptit.iot.service.DataSensorService;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class DataSensorServiceImpl implements DataSensorService {

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

    private final DataSensorRepository dataSensorRepository;

    @Override
    public LatestDataSensorResponse getLatestData() {
        List<DataSensor> temps = findLatest("temperature", 1);
        List<DataSensor> hums = findLatest("humidity", 1);
        List<DataSensor> lights = findLatest("light", 1);

        LatestDataSensorResponse response = new LatestDataSensorResponse();

        if (!temps.isEmpty()) {
            response.setTemperature(Double.parseDouble(temps.get(0).getValue()));
            response.setTimestamp(temps.get(0).getCreatedAt());
        }

        if (!hums.isEmpty()) {
            response.setHumidity(Double.parseDouble(hums.get(0).getValue()));
            if (response.getTimestamp() == null) {
                response.setTimestamp(hums.get(0).getCreatedAt());
            }
        }

        if (!lights.isEmpty()) {
            response.setLight(Double.parseDouble(lights.get(0).getValue()));
            if (response.getTimestamp() == null) {
                response.setTimestamp(lights.get(0).getCreatedAt());
            }
        }

        return response;
    }

    @Override
    public List<LatestDataSensorResponse> getChartData() {
        List<DataSensor> temps = findLatest("temperature", 12);
        List<DataSensor> hums = findLatest("humidity", 12);
        List<DataSensor> lights = findLatest("light", 12);

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
                if (response.getTimestamp() == null) {
                    response.setTimestamp(hums.get(i).getCreatedAt());
                }
            }

            if (i < lights.size()) {
                response.setLight(Double.parseDouble(lights.get(i).getValue()));
                if (response.getTimestamp() == null) {
                    response.setTimestamp(lights.get(i).getCreatedAt());
                }
            }

            responses.add(response);
        }

        Collections.reverse(responses);
        return responses;
    }

    private List<DataSensor> findLatest(String sensorName, int limit) {
        return dataSensorRepository
                .findBySensor_NameOrderByCreatedAtDesc(
                        sensorName,
                        PageRequest.of(0, limit))
                .getContent();
    }

    @Override
    public PageResponse<DataSensorResponse> searchDataSensors(
            int page,
            int size,
            String type,
            String search,
            String time,
            String searchMode) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        String safeType = type == null || type.isBlank() ? "All" : type.trim();
        String safeSearch = search == null ? "" : search.trim();
        String safeTime = time == null ? "" : time.trim();
        String safeMode = searchMode == null || searchMode.isBlank()
                ? "ALL"
                : searchMode.trim().toUpperCase(Locale.ROOT);

        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by("createdAt").descending());

        Specification<DataSensor> specification = buildSearchSpecification(safeType, safeSearch, safeTime, safeMode);

        Page<DataSensor> dataPage = dataSensorRepository.findAll(specification, pageable);

        List<DataSensorResponse> content = dataPage.getContent()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());

        return PageResponse.<DataSensorResponse>builder()
                .content(content)
                .page(dataPage.getNumber())
                .size(dataPage.getSize())
                .totalElements(dataPage.getTotalElements())
                .totalPages(dataPage.getTotalPages())
                .build();
    }

    private Specification<DataSensor> buildSearchSpecification(
            String sensorType,
            String search,
            String time,
            String searchMode) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (!"All".equalsIgnoreCase(sensorType)) {
                predicates.add(
                        cb.equal(
                                cb.lower(root.get("sensor").get("name")),
                                sensorType.toLowerCase(Locale.ROOT)));
            }

            // Nếu có param time cụ thể
            if (time != null && !time.isBlank()) {
                Predicate timePredicate = buildTimePredicate(root.get("createdAt"), cb, time);
                predicates.add(timePredicate != null ? timePredicate : cb.disjunction());
            }

            // Nếu có param search (giá trị cảm biến hoặc từ khóa thời gian tự do)
            if (search != null && !search.isBlank()) {
                if (time != null && !time.isBlank()) {
                    // Khi đã có time riêng, search đóng vai trò tìm theo giá trị cảm biến
                    String valueKeyword = normalizeValueKeyword(search);
                    predicates.add(cb.like(
                            cb.lower(root.get("value")),
                            valueKeyword.toLowerCase(Locale.ROOT) + "%"));
                } else if ("TIME".equalsIgnoreCase(searchMode)) {
                    Predicate searchTimePredicate = buildTimePredicate(root.get("createdAt"), cb, search);
                    predicates.add(searchTimePredicate != null ? searchTimePredicate : cb.disjunction());
                } else {
                    Predicate searchTimePredicate = buildTimePredicate(root.get("createdAt"), cb, search);
                    boolean hasTimeDelimiter = search.contains(":") || search.contains("/");
                    if (hasTimeDelimiter) {
                        predicates.add(searchTimePredicate != null ? searchTimePredicate : cb.disjunction());
                    } else {
                        String valueKeyword = normalizeValueKeyword(search);
                        Predicate valuePredicate = cb.like(
                                cb.lower(root.get("value")),
                                valueKeyword.toLowerCase(Locale.ROOT) + "%");

                        predicates.add(searchTimePredicate != null
                                ? cb.or(valuePredicate, searchTimePredicate)
                                : valuePredicate);
                    }
                }
            }

            return predicates.isEmpty()
                    ? cb.conjunction()
                    : cb.and(predicates.toArray(new Predicate[0]));
        };
    }

    private String normalizeValueKeyword(String search) {
        String trimmed = search.trim();

        String numeric = trimmed.replaceAll("[^0-9.\\\\-]", "");

        return numeric.isBlank() ? trimmed : numeric;
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

    private DataSensorResponse toResponse(DataSensor dataSensor) {
        return DataSensorResponse.builder()
                .id(dataSensor.getId())
                .sensorId(dataSensor.getSensor().getId())
                .sensorType(dataSensor.getSensor().getName())
                .value(dataSensor.getValue())
                .createdAt(dataSensor.getCreatedAt())
                .build();
    }
}
