package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.domain.DimDateEntity;
import com.github.serbentd.eve.batch.repository.DimDateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
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
@Slf4j
public class DateDimensionService {

    private static final int BATCH_SIZE = 500;

    private static final String DATE_STUB_SQL = """
            INSERT INTO analytics.dim_date (
                date_id, full_date, year_num, quarter, month_num, month_name,
                week_of_year, day_of_month, day_of_week, day_name, is_weekend
            ) VALUES (
                :dateId, :fullDate, :yearNum, :quarter, :monthNum, :monthName,
                :weekOfYear, :dayOfMonth, :dayOfWeek, :dayName, :isWeekend
            ) ON CONFLICT DO NOTHING
            """;

    private final DimDateRepository dimDateRepository;
    private final NamedParameterJdbcTemplate jdbcTemplate;

    public DateDimensionService(DimDateRepository dimDateRepository, DataSource dataSource) {
        this.dimDateRepository = dimDateRepository;
        this.jdbcTemplate = new NamedParameterJdbcTemplate(dataSource);
    }

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
     * Ensures that the specified calendar date exists in dim_date atomically via single-pass
     * {@code ON CONFLICT DO NOTHING}, avoiding concurrency check-then-act race conditions.
     *
     * @param date the date to ensure
     * @return the persistent or constructed DimDateEntity
     */
    @Transactional
    public DimDateEntity ensureDateExists(LocalDate date) {
        DimDateEntity entity = buildDateEntity(date);
        SqlParameterSource params = new MapSqlParameterSource()
                .addValue("dateId", entity.getDateId())
                .addValue("fullDate", entity.getFullDate())
                .addValue("yearNum", entity.getYearNum())
                .addValue("quarter", entity.getQuarter())
                .addValue("monthNum", entity.getMonthNum())
                .addValue("monthName", entity.getMonthName())
                .addValue("weekOfYear", entity.getWeekOfYear())
                .addValue("dayOfMonth", entity.getDayOfMonth())
                .addValue("dayOfWeek", entity.getDayOfWeek())
                .addValue("dayName", entity.getDayName())
                .addValue("isWeekend", entity.getIsWeekend());

        jdbcTemplate.update(DATE_STUB_SQL, params);
        return dimDateRepository.findById(entity.getDateId()).orElse(entity);
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
