package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.domain.DimDateEntity;
import com.github.serbentd.eve.batch.repository.DimDateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.TextStyle;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Service responsible for mathematically generating and populating the conformed
 * calendar dimension (dim_date) with pre-computed temporal attributes.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DateDimensionService {

    private static final int BATCH_SIZE = 500;
    private final DimDateRepository dimDateRepository;

    /**
     * Seeds the calendar dimension for the specified date range if not already populated.
     *
     * @param startDate the start date (inclusive)
     * @param endDate   the end date (inclusive)
     * @return the number of calendar days seeded
     */
    @Transactional
    public long seedDateDimension(LocalDate startDate, LocalDate endDate) {
        long existingCount = dimDateRepository.count();
        if (existingCount > 0) {
            log.info("dim_date already contains {} records, skipping generation", existingCount);
            return 0;
        }

        log.info("Generating dim_date dimension from {} to {}", startDate, endDate);
        List<DimDateEntity> batch = new ArrayList<>(BATCH_SIZE);
        long totalInserted = 0;

        LocalDate current = startDate;
        while (!current.isAfter(endDate)) {
            batch.add(buildDateEntity(current));

            if (batch.size() >= BATCH_SIZE) {
                dimDateRepository.saveAll(batch);
                totalInserted += batch.size();
                batch.clear();
            }

            current = current.plusDays(1);
        }

        if (!batch.isEmpty()) {
            dimDateRepository.saveAll(batch);
            totalInserted += batch.size();
            batch.clear();
        }

        log.info("Successfully seeded {} calendar days into dim_date", totalInserted);
        return totalInserted;
    }

    /**
     * Converts a LocalDate to an integer surrogate key (YYYYMMDD).
     *
     * @param date the date to convert
     * @return YYYYMMDD surrogate integer key
     */
    public static int toDateId(LocalDate date) {
        return (date.getYear() * 10000) + (date.getMonthValue() * 100) + date.getDayOfMonth();
    }

    /**
     * Ensures that the specified calendar date exists in dim_date, seeding it if absent.
     *
     * @param date the date to ensure
     * @return the persistent DimDateEntity
     */
    @Transactional
    public DimDateEntity ensureDateExists(LocalDate date) {
        int dateId = toDateId(date);
        return dimDateRepository.findById(dateId)
                .orElseGet(() -> dimDateRepository.saveAndFlush(buildDateEntity(date)));
    }

    private DimDateEntity buildDateEntity(LocalDate date) {
        int year = date.getYear();
        int month = date.getMonthValue();
        int day = date.getDayOfMonth();
        int dateId = toDateId(date);
        int quarter = ((month - 1) / 3) + 1;
        int weekOfYear = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
        DayOfWeek dayOfWeek = date.getDayOfWeek();
        boolean isWeekend = (dayOfWeek.equals(DayOfWeek.SATURDAY) || dayOfWeek.equals(DayOfWeek.SUNDAY));

        return DimDateEntity.builder()
                .dateId(dateId)
                .fullDate(date)
                .yearNum(year)
                .quarter(quarter)
                .monthNum(month)
                .monthName(date.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .weekOfYear(weekOfYear)
                .dayOfMonth(day)
                .dayOfWeek(dayOfWeek.getValue())
                .dayName(dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH))
                .isWeekend(isWeekend)
                .build();
    }
}
