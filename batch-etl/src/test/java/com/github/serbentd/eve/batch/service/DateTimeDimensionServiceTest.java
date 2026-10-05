package com.github.serbentd.eve.batch.service;

import com.github.serbentd.eve.batch.domain.DimDateEntity;
import com.github.serbentd.eve.batch.domain.DimTimeEntity;
import com.github.serbentd.eve.batch.repository.DimDateRepository;
import com.github.serbentd.eve.batch.repository.DimTimeRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class DateTimeDimensionServiceTest {

    private static final LocalDate START_DATE = LocalDate.of(2026, 1, 1);
    private static final LocalDate END_DATE = LocalDate.of(2026, 1, 10);
    private static final int EXPECTED_DAYS_COUNT = 10;
    private static final int MINUTES_PER_DAY = 1440;

    private final DateDimensionService dateDimensionService;
    private final TimeDimensionService timeDimensionService;
    private final DimDateRepository dimDateRepository;
    private final DimTimeRepository dimTimeRepository;

    DateTimeDimensionServiceTest(DateDimensionService dateDimensionService,
                                TimeDimensionService timeDimensionService,
                                DimDateRepository dimDateRepository,
                                DimTimeRepository dimTimeRepository) {
        this.dateDimensionService = dateDimensionService;
        this.timeDimensionService = timeDimensionService;
        this.dimDateRepository = dimDateRepository;
        this.dimTimeRepository = dimTimeRepository;
    }

    @Test
    @DisplayName("Should mathematically seed date dimension and compute correct temporal attributes")
    void shouldSeedDateDimensionCorrectly() {
        long seededCount = dateDimensionService.seedDateDimension(START_DATE, END_DATE);
        assertThat(seededCount).isEqualTo(EXPECTED_DAYS_COUNT);

        // Verify a specific date: Jan 3, 2026 (Saturday)
        LocalDate saturday = LocalDate.of(2026, 1, 3);
        Optional<DimDateEntity> entityOpt = dimDateRepository.findByFullDate(saturday);

        assertThat(entityOpt).isPresent();
        DimDateEntity entity = entityOpt.get();
        assertThat(entity.getDateId()).isEqualTo(20260103);
        assertThat(entity.getYearNum()).isEqualTo(2026);
        assertThat(entity.getMonthNum()).isEqualTo(1);
        assertThat(entity.getMonthName()).isEqualTo("January");
        assertThat(entity.getQuarter()).isEqualTo(1);
        assertThat(entity.getDayOfMonth()).isEqualTo(3);
        assertThat(entity.getDayOfWeek()).isEqualTo(6);
        assertThat(entity.getDayName()).isEqualTo("Saturday");
        assertThat(entity.getIsWeekend()).isTrue();

        // Idempotency: second run should skip
        long secondRun = dateDimensionService.seedDateDimension(START_DATE, END_DATE);
        assertThat(secondRun).isZero();
    }

    @Test
    @DisplayName("Should mathematically seed 1,440 time-of-day records and classify EVE Prime Time buckets")
    void shouldSeedTimeDimensionCorrectly() {
        long seededCount = timeDimensionService.seedTimeDimension();
        assertThat(seededCount).isEqualTo(MINUTES_PER_DAY);

        // Verify EU Prime Time: 19:30 UTC
        LocalTime euPrimeTime = LocalTime.of(19, 30);
        Optional<DimTimeEntity> euPrimeOpt = dimTimeRepository.findByTimeValue(euPrimeTime);
        assertThat(euPrimeOpt).isPresent();
        DimTimeEntity euPrime = euPrimeOpt.get();
        assertThat(euPrime.getTimeId()).isEqualTo(1930);
        assertThat(euPrime.getHour24()).isEqualTo(19);
        assertThat(euPrime.getHour12()).isEqualTo(7);
        assertThat(euPrime.getMinuteOfHour()).isEqualTo(30);
        assertThat(euPrime.getAmPm()).isEqualTo("PM");
        assertThat(euPrime.getDayPart()).isEqualTo("Evening");
        assertThat(euPrime.getEveTimeBucket()).isEqualTo("EU_PRIME");

        // Verify Downtime: 11:15 UTC
        LocalTime downtime = LocalTime.of(11, 15);
        Optional<DimTimeEntity> downtimeOpt = dimTimeRepository.findByTimeValue(downtime);
        assertThat(downtimeOpt).isPresent();
        assertThat(downtimeOpt.get().getEveTimeBucket()).isEqualTo("DOWNTIME_WINDOW");

        // Idempotency: second run should skip
        long secondRun = timeDimensionService.seedTimeDimension();
        assertThat(secondRun).isZero();
    }

    @Test
    @DisplayName("Should verify date dimension rows follow a strict gapless chronological sequence")
    void shouldSeedDatesInStrictGaplessChronologicalSequence() {
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2026, 1, 31); // 31 days in January
        dateDimensionService.seedDateDimension(start, end);

        // Fetch all seeded dates ordered by date_id ascending
        var allDates = dimDateRepository.findAll(
                com.github.serbentd.eve.batch.domain.QDimDateEntity.dimDateEntity.fullDate.between(start, end),
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "dateId")
        );

        var dateList = new java.util.ArrayList<DimDateEntity>();
        allDates.forEach(dateList::add);

        assertThat(dateList).hasSize(31);

        for (int i = 0; i < dateList.size(); i++) {
            DimDateEntity current = dateList.get(i);
            LocalDate expectedDate = start.plusDays(i);

            // Assert exact day-by-day progression
            assertThat(current.getFullDate()).isEqualTo(expectedDate);

            // Assert strictly increasing integer surrogate keys (e.g. 20260101 -> 20260102)
            if (i > 0) {
                DimDateEntity previous = dateList.get(i - 1);
                assertThat(current.getDateId()).isGreaterThan(previous.getDateId());
                assertThat(current.getFullDate()).isEqualTo(previous.getFullDate().plusDays(1));
            }
        }
    }

    @Test
    @DisplayName("Should verify time dimension contains all 1,440 minutes in exact minute-by-minute sequence")
    void shouldSeedAll1440MinutesInExactMinuteSequence() {
        timeDimensionService.seedTimeDimension();

        var allTimes = dimTimeRepository.findAll(
                org.springframework.data.domain.Sort.by(org.springframework.data.domain.Sort.Direction.ASC, "timeId")
        );

        assertThat(allTimes).hasSize(MINUTES_PER_DAY);

        for (int i = 0; i < allTimes.size(); i++) {
            DimTimeEntity entity = allTimes.get(i);
            int expectedHour = i / 60;
            int expectedMinute = i % 60;
            int expectedTimeId = (expectedHour * 100) + expectedMinute;

            assertThat(entity.getHour24()).isEqualTo(expectedHour);
            assertThat(entity.getMinuteOfHour()).isEqualTo(expectedMinute);
            assertThat(entity.getTimeId()).isEqualTo(expectedTimeId);
            assertThat(entity.getTimeValue()).isEqualTo(LocalTime.of(expectedHour, expectedMinute));

            if (i > 0) {
                DimTimeEntity previous = allTimes.get(i - 1);
                assertThat(entity.getTimeId()).isGreaterThan(previous.getTimeId());
            }
        }
    }

    @Test
    @DisplayName("Should seed across multiple batches when date range exceeds batch size of 500")
    void shouldSeedAcrossMultipleBatchesWhenRangeExceedsBatchSize() {
        dimDateRepository.deleteAll();
        LocalDate start = LocalDate.of(2026, 1, 1);
        LocalDate end = LocalDate.of(2027, 6, 30); // 546 days
        long count = dateDimensionService.seedDateDimension(start, end);
        assertThat(count).isEqualTo(546);
        assertThat(dimDateRepository.count()).isEqualTo(546);
    }
}
