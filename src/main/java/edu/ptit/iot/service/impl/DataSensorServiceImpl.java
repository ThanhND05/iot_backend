package edu.ptit.iot.service.impl;

import edu.ptit.iot.dto.response.DataSensorResponse;
import edu.ptit.iot.dto.response.LatestDataSensorResponse;
import edu.ptit.iot.dto.response.PageResponse;
import edu.ptit.iot.entity.DataSensor;
import edu.ptit.iot.repository.DataSensorRepository;
import edu.ptit.iot.service.DataSensorService;
import jakarta.persistence.criteria.CriteriaBuilder;
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

    // date + hour only (no minute) — e.g. "29/09/2026 15" or "2026-09-29 15"
    private static final Pattern YYYY_MM_DD_HOUR = Pattern
            .compile("^(\\d{4})[-/](\\d{1,2})[-/](\\d{1,2})[ T](\\d{1,2})$");

    private static final Pattern DD_MM_YYYY_HOUR = Pattern
            .compile("^(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})[ T](\\d{1,2})$");

    // 4-digit year only — e.g. "2026"
    private static final Pattern YEAR_ONLY = Pattern.compile("^(\\d{4})$");

    // 1–2 digit day of month — e.g. "29"
    private static final Pattern DAY_ONLY = Pattern.compile("^(\\d{1,2})$");

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
        List<DataSensor> temps = findLatest("temperature", 10);
        List<DataSensor> hums = findLatest("humidity", 10);
        List<DataSensor> lights = findLatest("light", 10);

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
            String searchMode) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);

        String safeType = type == null || type.isBlank() ? "All" : type.trim();

        String safeSearch = search == null ? "" : search.trim();

        String safeMode = searchMode == null || searchMode.isBlank()
                ? "ALL"
                : searchMode.trim().toUpperCase(Locale.ROOT);

        Pageable pageable = PageRequest.of(
                safePage,
                safeSize,
                Sort.by("createdAt").descending());

        Specification<DataSensor> specification = buildSearchSpecification(safeType, safeSearch, safeMode);

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
            String searchMode) {
        return (root, query, cb) -> {
            List<Predicate> predicates = new ArrayList<>();

            if (!"All".equalsIgnoreCase(sensorType)) {
                predicates.add(
                        cb.equal(
                                root.get("sensor").get("name"),
                                sensorType));
            }

            if (search == null || search.isBlank()) {
                return predicates.isEmpty()
                        ? cb.conjunction()
                        : cb.and(predicates.toArray(new Predicate[0]));
            }

            Predicate timePredicate = buildTimePredicate(root.get("createdAt"), cb, search);

            if ("TIME".equalsIgnoreCase(searchMode)) {
                predicates.add(
                        timePredicate != null
                                ? timePredicate
                                : cb.disjunction());
            } else {
                String valueKeyword = normalizeValueKeyword(search);

                Predicate valuePredicate = cb.like(
                        cb.lower(root.get("value")),
                        valueKeyword.toLowerCase(Locale.ROOT) + "%");

                predicates.add(
                        timePredicate != null
                                ? cb.or(valuePredicate, timePredicate)
                                : valuePredicate);
            }

            return cb.and(predicates.toArray(new Predicate[0]));
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

            matcher = YYYY_MM_DD.matcher(search);
            if (matcher.matches()) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));

                return buildDayRange(createdAt, cb, date);
            }

            matcher = DD_MM_YYYY.matcher(search);
            if (matcher.matches()) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(1)));

                return buildDayRange(createdAt, cb, date);
            }

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

            // yyyy-MM-dd HH (date + hour, no minute)
            matcher = YYYY_MM_DD_HOUR.matcher(search);
            if (matcher.matches()) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(1)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(3)));
                int hour = Integer.parseInt(matcher.group(4));
                if (hour > 23) return null;
                return buildHourRange(createdAt, cb, date, hour);
            }

            // dd/MM/yyyy HH (date + hour, no minute)
            matcher = DD_MM_YYYY_HOUR.matcher(search);
            if (matcher.matches()) {
                LocalDate date = LocalDate.of(
                        Integer.parseInt(matcher.group(3)),
                        Integer.parseInt(matcher.group(2)),
                        Integer.parseInt(matcher.group(1)));
                int hour = Integer.parseInt(matcher.group(4));
                if (hour > 23) return null;
                return buildHourRange(createdAt, cb, date, hour);
            }

            // 4-digit year only — e.g. "2026"
            matcher = YEAR_ONLY.matcher(search);
            if (matcher.matches()) {
                int year = Integer.parseInt(matcher.group(1));
                return cb.equal(
                        cb.function("year", Integer.class, createdAt),
                        year);
            }

            // 1–2 digit day of month — e.g. "29"
            matcher = DAY_ONLY.matcher(search);
            if (matcher.matches()) {
                int day = Integer.parseInt(matcher.group(1));
                if (day < 1 || day > 31) return null;
                return cb.equal(
                        cb.function("day", Integer.class, createdAt),
                        day);
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

    private Predicate buildHourRange(
            Path<LocalDateTime> createdAt,
            CriteriaBuilder cb,
            LocalDate date,
            int hour) {
        LocalDateTime start = date.atTime(hour, 0, 0);
        LocalDateTime end = start.plusHours(1);

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
