package com.github.serbentd.eve.worker.service;

import com.github.serbentd.eve.worker.config.MarketProperties;
import com.github.serbentd.eve.worker.domain.MarketOrderEntity;
import com.github.serbentd.eve.worker.domain.OrderStatus;
import com.github.serbentd.eve.worker.event.MarketOrderEvent;
import com.github.serbentd.eve.worker.event.RegionSnapshotCompletedEvent;
import com.github.serbentd.eve.worker.repository.MarketOrderRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Transactional business logic service for persisting EVE Online market orders
 * and executing crash-safe snapshot reconciliation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderPersistenceService {

    private final MarketOrderRepository marketOrderRepository;
    private final EntityManager entityManager;
    private final MarketProperties properties;

    /**
     * Persists a batch of market orders received from RabbitMQ.
     * Partitions the orders into chunks configured by {@link MarketProperties#batchSize()},
     * updates existing orders while preserving their initial {@code createdAt} discovery time,
     * or inserts newly discovered orders with {@code status = ACTIVE}.
     *
     * @param events list of market order event payloads
     */
    @Transactional
    public void persistOrders(List<MarketOrderEvent> events) {
        if (events == null || events.isEmpty()) {
            return;
        }

        int batchSize = Math.max(1, properties.batchSize());
        log.info("Persisting {} market orders in chunks of up to {}", events.size(), batchSize);

        for (int i = 0; i < events.size(); i += batchSize) {
            List<MarketOrderEvent> chunk = events.subList(i, Math.min(events.size(), i + batchSize));
            persistChunk(chunk);
        }
    }

    private void persistChunk(List<MarketOrderEvent> chunk) {
        Set<Long> orderIds = chunk.stream()
                .map(MarketOrderEvent::orderId)
                .collect(Collectors.toSet());

        Map<Long, MarketOrderEntity> existingOrders = marketOrderRepository.findAllById(orderIds).stream()
                .collect(Collectors.toMap(MarketOrderEntity::getOrderId, Function.identity()));

        OffsetDateTime now = OffsetDateTime.now();
        List<MarketOrderEntity> entitiesToSave = new ArrayList<>(chunk.size());

        for (MarketOrderEvent event : chunk) {
            MarketOrderEntity entity = existingOrders.get(event.orderId());

            if (entity != null) {
                // Update active attributes
                entity.setPrice(event.price());
                entity.setVolumeRemain(event.volumeRemain());
                entity.setVolumeTotal(event.volumeTotal());
                entity.setMinVolume(event.minVolume());
                entity.setDuration(event.duration());
                entity.setIsBuyOrder(event.isBuyOrder());
                entity.setOrderRange(event.orderRange());
                entity.setStatus(OrderStatus.ACTIVE);
                entity.setIsActive(true);
                entity.setSnapshotId(event.snapshotId());
                entity.setPolledAt(event.polledAt());
                entity.setUpdatedAt(now);
                entity.setFinishedAt(null);
            } else {
                // Insert new order discovery
                entity = MarketOrderEntity.builder()
                        .orderId(event.orderId())
                        .typeId(event.typeId())
                        .regionId(event.regionId())
                        .systemId(event.systemId())
                        .locationId(event.locationId())
                        .price(event.price())
                        .volumeRemain(event.volumeRemain())
                        .volumeTotal(event.volumeTotal())
                        .minVolume(event.minVolume())
                        .duration(event.duration())
                        .isBuyOrder(event.isBuyOrder())
                        .orderRange(event.orderRange())
                        .status(OrderStatus.ACTIVE)
                        .isActive(true)
                        .snapshotId(event.snapshotId())
                        .issued(event.issued())
                        .polledAt(event.polledAt())
                        .createdAt(now)
                        .updatedAt(now)
                        .finishedAt(null)
                        .build();
            }

            entitiesToSave.add(entity);
        }

        marketOrderRepository.saveAll(entitiesToSave);
        entityManager.flush();
        entityManager.clear();
        log.debug("Successfully saved chunk of {} market orders to database", entitiesToSave.size());
    }

    /**
     * Executes atomic reconciliation for a completed regional snapshot.
     * Marks any order in the region that was NOT updated in this snapshot run as {@code FINISHED}.
     *
     * @param event the region snapshot completion event
     * @return number of orders transitioned to {@code FINISHED}
     */
    @Transactional
    public int reconcileSnapshot(RegionSnapshotCompletedEvent event) {
        log.info("Reconciling snapshot for region {} with snapshot ID {}", event.regionId(), event.snapshotId());

        OffsetDateTime now = OffsetDateTime.now();

        int updatedCount = entityManager.createQuery(
                        "UPDATE MarketOrderEntity m " +
                                "SET m.status = :finishedStatus, " +
                                "    m.isActive = false, " +
                                "    m.finishedAt = :finishedAt, " +
                                "    m.updatedAt = :now " +
                                "WHERE m.regionId = :regionId " +
                                "  AND (m.snapshotId IS NULL OR m.snapshotId != :snapshotId) " +
                                "  AND m.isActive = true")
                .setParameter("finishedStatus", OrderStatus.FINISHED)
                .setParameter("finishedAt", event.completedAt())
                .setParameter("now", now)
                .setParameter("regionId", event.regionId())
                .setParameter("snapshotId", event.snapshotId())
                .executeUpdate();

        log.info("Reconciliation complete for region {}: marked {} orders as FINISHED (expected total: {})",
                event.regionId(), updatedCount, event.totalOrders());

        return updatedCount;
    }
}
