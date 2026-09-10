package com.github.serbentd.eve.worker.repository;

import com.github.serbentd.eve.worker.domain.MarketOrderEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA and QueryDSL repository for {@link MarketOrderEntity}.
 * Provides CRUD operations, order book depth queries, top-of-book lookups,
 * and snapshot synchronization support for EVE market orders.
 */
@Repository
public interface MarketOrderRepository extends JpaRepository<MarketOrderEntity, Long>, QuerydslPredicateExecutor<MarketOrderEntity> {

    /**
     * Finds all active market orders within a specific EVE solar region.
     *
     * @param regionId the EVE region ID (e.g., 10000002 for The Forge)
     * @return list of matching market orders
     */
    List<MarketOrderEntity> findByRegionId(Long regionId);

    /**
     * Finds all active market orders for a specific item type within a region.
     *
     * @param regionId the EVE region ID
     * @param typeId   the EVE item type ID
     * @return list of matching market orders
     */
    List<MarketOrderEntity> findByRegionIdAndTypeId(Long regionId, Long typeId);

    /**
     * Finds all orders of a specific type and side (buy/sell) in a region ordered by price descending (highest bid first).
     *
     * @param regionId   the EVE region ID
     * @param typeId     the EVE item type ID
     * @param isBuyOrder true for buy orders (bids), false for sell orders (asks)
     * @return list of market orders ordered by price descending
     */
    List<MarketOrderEntity> findByRegionIdAndTypeIdAndIsBuyOrderOrderByPriceDesc(Long regionId, Long typeId, Boolean isBuyOrder);

    /**
     * Finds all orders of a specific type and side (buy/sell) in a region ordered by price ascending (lowest ask first).
     *
     * @param regionId   the EVE region ID
     * @param typeId     the EVE item type ID
     * @param isBuyOrder true for buy orders (bids), false for sell orders (asks)
     * @return list of market orders ordered by price ascending
     */
    List<MarketOrderEntity> findByRegionIdAndTypeIdAndIsBuyOrderOrderByPriceAsc(Long regionId, Long typeId, Boolean isBuyOrder);

    /**
     * Finds the highest buy order (best bid) or highest sell order for an item in a region.
     *
     * @param regionId   the EVE region ID
     * @param typeId     the EVE item type ID
     * @param isBuyOrder true for buy orders, false for sell orders
     * @return optional best order
     */
    Optional<MarketOrderEntity> findFirstByRegionIdAndTypeIdAndIsBuyOrderOrderByPriceDesc(Long regionId, Long typeId, Boolean isBuyOrder);

    /**
     * Finds the lowest sell order (best ask / offer) or lowest buy order for an item in a region.
     *
     * @param regionId   the EVE region ID
     * @param typeId     the EVE item type ID
     * @param isBuyOrder true for buy orders, false for sell orders
     * @return optional best order
     */
    Optional<MarketOrderEntity> findFirstByRegionIdAndTypeIdAndIsBuyOrderOrderByPriceAsc(Long regionId, Long typeId, Boolean isBuyOrder);

    /**
     * Finds all active market orders for an item hosted at a specific NPC station or player Citadel.
     *
     * @param locationId station ID or Upwell citadel ID
     * @param typeId     the EVE item type ID
     * @return list of matching market orders
     */
    List<MarketOrderEntity> findByLocationIdAndTypeId(Long locationId, Long typeId);

    /**
     * Deletes orders in a region that were not updated on or after the specified cutoff timestamp.
     * Used during snapshot synchronization to prune fulfilled or cancelled orders.
     *
     * @param regionId the EVE region ID
     * @param cutoff   the snapshot synchronization start timestamp
     * @return number of deleted stale orders
     */
    long deleteByRegionIdAndUpdatedAtBefore(Long regionId, OffsetDateTime cutoff);

    /**
     * Finds orders in a region that were not updated on or after the specified cutoff timestamp.
     * Used to identify fulfilled or cancelled orders prior to reconciliation or historical archiving.
     *
     * @param regionId the EVE region ID
     * @param cutoff   the snapshot synchronization start timestamp
     * @return list of stale market orders
     */
    List<MarketOrderEntity> findByRegionIdAndUpdatedAtBefore(Long regionId, OffsetDateTime cutoff);
}
