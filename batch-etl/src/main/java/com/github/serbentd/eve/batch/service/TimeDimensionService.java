package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.domain.DimTimeEntity;
import com.github.serbentd.eve.batch.repository.DimTimeRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for mathematically generating and populating the conformed
 * time-of-day dimension (dim_time) with clock attributes and EVE Online player timezone buckets.
 */
@Service
@Slf4j
public class TimeDimensionService {

    private static final int MINUTES_PER_DAY = 1440;

    private static final String TIME_STUB_SQL = """
            INSERT INTO analytics.dim_time (
                time_id, time_value, hour_24, hour_12, minute_of_hour,
                time_formatted_24, time_formatted_12, am_pm, day_part, eve_time_bucket
            ) VALUES (
                :timeId, :timeValue, :hour24, :hour12, :minuteOfHour,
                :timeFormatted24, :timeFormatted12, :amPm, :dayPart, :eveTimeBucket
            ) ON CONFLICT DO NOTHING
            """;

    private final DimTimeRepository dimTimeRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public TimeDimensionService(DimTimeRepository dimTimeRepository, DataSource dataSource) {
        this.dimTimeRepository = dimTimeRepository;
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

    /**
     * Seeds the 1,440 minutes of a 24-hour day into dim_time if not already present.
     *
     * @return the number of time records seeded
     */
    @Transactional
    public long seedTimeDimension() {
        long existingCount = dimTimeRepository.count();
        if (existingCount >= MINUTES_PER_DAY) {
            log.info("dim_time already contains {} records, skipping generation", existingCount);
            return 0;
        }

        log.info("Generating dim_time dimension (1,440 minutes)");
        List<DimTimeEntity> records = new ArrayList<>(MINUTES_PER_DAY);

        for (int hour = 0; hour < 24; hour++) {
            for (int minute = 0; minute < 60; minute++) {
                records.add(buildTimeEntity(hour, minute));
            }
        }

        dimTimeRepository.saveAll(records);
        log.info("Successfully seeded {} records into dim_time", records.size());
        return records.size();
    }

    /**
     * Converts a LocalTime to an integer surrogate key (HHmm).
     *
     * @param time the time to convert
     * @return HHmm surrogate integer key
     */
    public static int toTimeId(LocalTime time) {
        return (time.getHour() * 100) + time.getMinute();
    }

    /**
     * Ensures that the specified minute exists in dim_time atomically via single-pass
     * {@code ON CONFLICT DO NOTHING}, avoiding concurrency check-then-act race conditions.
     *
     * @param time the time to ensure
     * @return the persistent or constructed DimTimeEntity
     */
    @Transactional
    public DimTimeEntity ensureTimeExists(LocalTime time) {
        DimTimeEntity entity = buildTimeEntity(time.getHour(), time.getMinute());
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("timeId", entity.getTimeId())
                .addValue("timeValue", entity.getTimeValue())
                .addValue("hour24", entity.getHour24())
                .addValue("hour12", entity.getHour12())
                .addValue("minuteOfHour", entity.getMinuteOfHour())
                .addValue("timeFormatted24", entity.getTimeFormatted24())
                .addValue("timeFormatted12", entity.getTimeFormatted12())
                .addValue("amPm", entity.getAmPm())
                .addValue("dayPart", entity.getDayPart())
                .addValue("eveTimeBucket", entity.getEveTimeBucket());

        jdbcTemplate.update(TIME_STUB_SQL, params);
        return dimTimeRepository.findById(entity.getTimeId()).orElse(entity);
    }

    private DimTimeEntity buildTimeEntity(int hour, int minute) {
        int timeId = (hour * 100) + minute;
        LocalTime timeValue = LocalTime.of(hour, minute);
        int hour12 = (hour % 12 == 0) ? 12 : hour % 12;
        String amPm = (hour < 12) ? "AM" : "PM";
        String timeFormatted24 = String.format("%02d:%02d", hour, minute);
        String timeFormatted12 = String.format("%02d:%02d %s", hour12, minute, amPm);

        String dayPart;
        if (hour < 6) {
            dayPart = "Night";
        } else if (hour < 12) {
            dayPart = "Morning";
        } else if (hour < 18) {
            dayPart = "Afternoon";
        } else {
            dayPart = "Evening";
        }

        String eveTimeBucket;
        if (hour == 11 && minute < 30) {
            eveTimeBucket = "DOWNTIME_WINDOW";
        } else if (hour >= 18 && hour <= 22) {
            eveTimeBucket = "EU_PRIME";
        } else if (hour <= 4) {
            eveTimeBucket = "US_PRIME";
        } else {
            eveTimeBucket = "OFF_PEAK";
        }

        return DimTimeEntity.builder()
                .timeId(timeId)
                .timeValue(timeValue)
                .hour24(hour)
                .hour12(hour12)
                .minuteOfHour(minute)
                .timeFormatted24(timeFormatted24)
                .timeFormatted12(timeFormatted12)
                .amPm(amPm)
                .dayPart(dayPart)
                .eveTimeBucket(eveTimeBucket)
                .build();
    }
}
