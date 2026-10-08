package com.github.serbentd.eve.batch.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.LocalDate;
import java.util.Objects;

@Entity
@Table(name = "dim_date")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class DimDateEntity {

    @Id
    @Column(name = "date_id", nullable = false)
    private Integer dateId;

    @Column(name = "full_date", nullable = false, unique = true)
    private LocalDate fullDate;

    @Column(name = "year_num", nullable = false)
    private Integer yearNum;

    @Column(name = "quarter", nullable = false)
    private Integer quarter;

    @Column(name = "month_num", nullable = false)
    private Integer monthNum;

    @Column(name = "month_name", nullable = false, length = 16)
    private String monthName;

    @Column(name = "week_of_year", nullable = false)
    private Integer weekOfYear;

    @Column(name = "day_of_month", nullable = false)
    private Integer dayOfMonth;

    @Column(name = "day_of_week", nullable = false)
    private Integer dayOfWeek;

    @Column(name = "day_name", nullable = false, length = 16)
    private String dayName;

    @Column(name = "is_weekend", nullable = false)
    private Boolean isWeekend;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DimDateEntity that)) return false;
        return Objects.equals(dateId, that.dateId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(dateId);
    }
}
