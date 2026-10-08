package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.domain.DimTimeEntity;
import com.github.serbentd.eve.batch.repository.DimTimeRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for mathematically generating and populating the conformed
 * time-of-day dimension (dim_time) with clock attributes and EVE Online player timezone buckets.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TimeDimensionService {

    private static final int MINUTES_PER_DAY = 1440;
    private final DimTimeRepository dimTimeRepository;

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
        } else if (hour >= 5 && hour <= 10) {
            eveTimeBucket = "AU_ASIA_TIME";
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
