package com.github.serbentd.eve.batch.dto;

import java.math.BigDecimal;

/**
 * Immutable transit record representing an NPC station or location extracted from an SDE feed.
 *
 * @param stationId       EVE station identifier
 * @param stationName     Full station name
 * @param solarSystemId   Solar system ID where the station is located
 * @param solarSystemName Solar system name
 * @param regionId        Region ID where the station resides
 * @param securityStatus  True solar system security status
 * @param securityClass   Security classification (HIGH_SEC, LOW_SEC, NULL_SEC)
 * @param isStructure     Whether this location is a player structure or NPC station
 */
public record SdeStationDto(
        Long stationId,
        String stationName,
        Long solarSystemId,
        String solarSystemName,
        Long regionId,
        BigDecimal securityStatus,
        String securityClass,
        Boolean isStructure
) {}
