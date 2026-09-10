package com.github.serbentd.eve.worker.repository;

import com.github.serbentd.eve.worker.domain.MarketOrderEntity;
import com.github.serbentd.eve.worker.domain.OrderStatus;
import com.github.serbentd.eve.worker.domain.QMarketOrderEntity;
import com.querydsl.core.types.dsl.BooleanExpression;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MarketOrderRepositorySliceTest {

    private final MarketOrderRepository repository;
    private final TestEntityManager entityManager;

    MarketOrderRepositorySliceTest(MarketOrderRepository repository, TestEntityManager entityManager) {
        this.repository = repository;
        this.entityManager = entityManager;
    }

    @Test
    void findByRegionIdAndTypeId_shouldReturnFilteredOrders() {
        OffsetDateTime now = OffsetDateTime.now();
        entityManager.persist(createOrder(1L, 34L, 10000002L, 30000142L, 60003760L, 10.0, 50, false, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(2L, 34L, 10000002L, 30000142L, 60003760L, 12.0, 60, false, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(3L, 35L, 10000002L, 30000142L, 60003760L, 20.0, 70, false, OrderStatus.ACTIVE, true, now));
        entityManager.flush();

        List<MarketOrderEntity> result = repository.findByRegionIdAndTypeId(10000002L, 34L);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(MarketOrderEntity::getOrderId).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    void findBestBidAndBestAsk_shouldReturnTopOfBook() {
        OffsetDateTime now = OffsetDateTime.now();
        // Buy orders (bids): 10.0 and 15.5 -> best bid is 15.5
        entityManager.persist(createOrder(10L, 34L, 10000002L, 30000142L, 60003760L, 10.0, 100, true, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(11L, 34L, 10000002L, 30000142L, 60003760L, 15.5, 50, true, OrderStatus.ACTIVE, true, now));

        // Sell orders (asks): 16.0 and 20.0 -> best ask is 16.0
        entityManager.persist(createOrder(20L, 34L, 10000002L, 30000142L, 60003760L, 16.0, 80, false, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(21L, 34L, 10000002L, 30000142L, 60003760L, 20.0, 90, false, OrderStatus.ACTIVE, true, now));
        entityManager.flush();

        Optional<MarketOrderEntity> bestBid = repository.findFirstByRegionIdAndTypeIdAndIsBuyOrderOrderByPriceDesc(10000002L, 34L, true);
        assertThat(bestBid).isPresent();
        assertThat(bestBid.get().getOrderId()).isEqualTo(11L);
        assertThat(bestBid.get().getPrice()).isEqualByComparingTo(BigDecimal.valueOf(15.5));

        Optional<MarketOrderEntity> bestAsk = repository.findFirstByRegionIdAndTypeIdAndIsBuyOrderOrderByPriceAsc(10000002L, 34L, false);
        assertThat(bestAsk).isPresent();
        assertThat(bestAsk.get().getOrderId()).isEqualTo(20L);
        assertThat(bestAsk.get().getPrice()).isEqualByComparingTo(BigDecimal.valueOf(16.0));
    }

    @Test
    void findByRegionIdAndTypeIdAndIsBuyOrder_shouldOrderBooksCorrectly() {
        OffsetDateTime now = OffsetDateTime.now();
        // Buy orders with unsorted prices
        entityManager.persist(createOrder(101L, 34L, 10000002L, 30000142L, 60003760L, 10.0, 10, true, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(102L, 34L, 10000002L, 30000142L, 60003760L, 15.0, 10, true, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(103L, 34L, 10000002L, 30000142L, 60003760L, 12.0, 10, true, OrderStatus.ACTIVE, true, now));

        // Sell orders with unsorted prices
        entityManager.persist(createOrder(201L, 34L, 10000002L, 30000142L, 60003760L, 25.0, 10, false, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(202L, 34L, 10000002L, 30000142L, 60003760L, 18.0, 10, false, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(203L, 34L, 10000002L, 30000142L, 60003760L, 20.0, 10, false, OrderStatus.ACTIVE, true, now));
        entityManager.flush();

        // Bids: highest price first (descending)
        List<MarketOrderEntity> bids = repository.findByRegionIdAndTypeIdAndIsBuyOrderOrderByPriceDesc(10000002L, 34L, true);
        assertThat(bids).extracting(MarketOrderEntity::getOrderId).containsExactly(102L, 103L, 101L);

        // Asks: lowest price first (ascending)
        List<MarketOrderEntity> asks = repository.findByRegionIdAndTypeIdAndIsBuyOrderOrderByPriceAsc(10000002L, 34L, false);
        assertThat(asks).extracting(MarketOrderEntity::getOrderId).containsExactly(202L, 203L, 201L);
    }

    @Test
    void findByLocationIdAndTypeId_shouldFilterByStation() {
        OffsetDateTime now = OffsetDateTime.now();
        Long jitaStation = 60003760L;
        Long amarrStation = 60008494L;

        entityManager.persist(createOrder(301L, 34L, 10000002L, 30000142L, jitaStation, 10.0, 10, false, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(302L, 34L, 10000043L, 30002187L, amarrStation, 10.5, 10, false, OrderStatus.ACTIVE, true, now));
        entityManager.flush();

        List<MarketOrderEntity> jitaOrders = repository.findByLocationIdAndTypeId(jitaStation, 34L);

        assertThat(jitaOrders).hasSize(1);
        assertThat(jitaOrders.getFirst().getOrderId()).isEqualTo(301L);
    }

    @Test
    void deleteByRegionIdAndUpdatedAtBefore_shouldPruneStaleOrders() {
        OffsetDateTime cutoff = OffsetDateTime.now().minusHours(1);

        // Stale order updated 2 hours ago
        entityManager.persist(createOrder(401L, 34L, 10000002L, 30000142L, 60003760L, 10.0, 10, false, OrderStatus.ACTIVE, true, cutoff.minusHours(1)));
        // Fresh order updated 10 minutes ago
        entityManager.persist(createOrder(402L, 34L, 10000002L, 30000142L, 60003760L, 10.0, 10, false, OrderStatus.ACTIVE, true, cutoff.plusMinutes(50)));
        entityManager.flush();

        long deletedCount = repository.deleteByRegionIdAndUpdatedAtBefore(10000002L, cutoff);

        assertThat(deletedCount).isEqualTo(1L);
        assertThat(repository.existsById(401L)).isFalse();
        assertThat(repository.existsById(402L)).isTrue();
    }

    @Test
    void queryDslPredicate_shouldFilterByActiveOrders() {
        OffsetDateTime now = OffsetDateTime.now();
        QMarketOrderEntity qOrder = QMarketOrderEntity.marketOrderEntity;

        entityManager.persist(createOrder(501L, 34L, 10000002L, 30000142L, 60003760L, 12.0, 10, false, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(502L, 34L, 10000002L, 30000142L, 60003760L, 18.0, 10, false, OrderStatus.ACTIVE, true, now));
        entityManager.persist(createOrder(503L, 34L, 10000002L, 30000142L, 60003760L, 25.0, 10, false, OrderStatus.FINISHED, false, now));
        entityManager.flush();

        BooleanExpression predicate = qOrder.isActive.isTrue()
                .and(qOrder.price.gt(BigDecimal.valueOf(15.0)))
                .and(qOrder.regionId.eq(10000002L));

        Iterable<MarketOrderEntity> result = repository.findAll(predicate);

        assertThat(result).hasSize(1);
        assertThat(result.iterator().next().getOrderId()).isEqualTo(502L);
    }

    private MarketOrderEntity createOrder(
            Long orderId,
            Long typeId,
            Long regionId,
            Long systemId,
            Long locationId,
            double price,
            long volumeRemain,
            boolean isBuyOrder,
            OrderStatus status,
            boolean isActive,
            OffsetDateTime updatedAt
    ) {
        return MarketOrderEntity.builder()
                .orderId(orderId)
                .typeId(typeId)
                .regionId(regionId)
                .systemId(systemId)
                .locationId(locationId)
                .price(BigDecimal.valueOf(price))
                .volumeRemain(volumeRemain)
                .volumeTotal(100L)
                .minVolume(1)
                .duration(90)
                .isBuyOrder(isBuyOrder)
                .orderRange("region")
                .status(status)
                .isActive(isActive)
                .snapshotId("snap-1")
                .issued(updatedAt)
                .polledAt(updatedAt)
                .createdAt(updatedAt)
                .updatedAt(updatedAt)
                .build();
    }
}
