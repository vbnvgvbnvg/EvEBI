package com.github.serbentd.eve.batch.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Immutable representation of a New Eden entity resolved via CCP's ESI Universe Names endpoint.
 *
 * @param id       natural entity identifier
 * @param name     official universe name
 * @param category entity category (e.g. "region", "solar_system", "inventory_type")
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EsiUniverseNameDto(
        Long id,
        String name,
        String category
) {}
