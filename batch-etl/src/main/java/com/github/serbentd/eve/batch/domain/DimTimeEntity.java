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

import java.time.LocalTime;
import java.util.Objects;

@Entity
@Table(name = "dim_time")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class DimTimeEntity {

    @Id
    @Column(name = "time_id", nullable = false)
    private Integer timeId;

    @Column(name = "time_value", nullable = false, unique = true)
    private LocalTime timeValue;

    @Column(name = "hour_24", nullable = false)
    private Integer hour24;

    @Column(name = "hour_12", nullable = false)
    private Integer hour12;

    @Column(name = "minute_of_hour", nullable = false)
    private Integer minuteOfHour;

    @Column(name = "time_formatted_24", nullable = false, length = 5)
    private String timeFormatted24;

    @Column(name = "time_formatted_12", nullable = false, length = 8)
    private String timeFormatted12;

    @Column(name = "am_pm", nullable = false, length = 2)
    private String amPm;

    @Column(name = "day_part", nullable = false, length = 16)
    private String dayPart;

    @Column(name = "eve_time_bucket", nullable = false, length = 32)
    private String eveTimeBucket;

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof DimTimeEntity that)) return false;
        return Objects.equals(timeId, that.timeId);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(timeId);
    }
}
