package br.com.luma.seller.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class SellerMetricsRepository {

    private static final String METRICS_SELECT = """
            SELECT s.id AS seller_id,
                COUNT(ci.id) FILTER (WHERE ci.status = 'COM_VENDEDORA' AND c.status = 'ABERTA') AS pieces_in_hand,
                COALESCE(SUM(ci.value_cents_snapshot) FILTER (
                    WHERE ci.status = 'COM_VENDEDORA' AND c.status = 'ABERTA'), 0) AS value_in_hand_cents,
                MIN(c.due_at) FILTER (WHERE c.status = 'ABERTA' AND ci.status = 'COM_VENDEDORA') AS next_due_at,
                COUNT(DISTINCT c.id) FILTER (WHERE c.status = 'ABERTA' AND ci.status = 'COM_VENDEDORA') AS open_lots,
                COUNT(ci.id) FILTER (WHERE ci.status = 'VENDIDA') AS total_sold_count,
                COALESCE(SUM(ci.value_cents_snapshot) FILTER (WHERE ci.status = 'VENDIDA'), 0) AS total_sold_value_cents,
                COALESCE(SUM(ROUND(ci.value_cents_snapshot::numeric * ci.commission_rate_snapshot / 100))
                    FILTER (WHERE ci.status = 'VENDIDA'), 0) AS total_commission_cents
            FROM sellers s
            LEFT JOIN consignments c ON c.seller_id = s.id
            LEFT JOIN consignment_items ci ON ci.consignment_id = c.id
            """;

    private static final RowMapper<SellerOperationalMetrics> ROW_MAPPER = SellerMetricsRepository::mapRow;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public SellerMetricsRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Map<UUID, SellerOperationalMetrics> findForSellers(Collection<UUID> sellerIds) {
        if (sellerIds.isEmpty()) {
            return Map.of();
        }
        String sql = METRICS_SELECT + " WHERE s.id IN (:sellerIds) GROUP BY s.id";
        MapSqlParameterSource parameters = new MapSqlParameterSource("sellerIds", sellerIds);
        List<SellerOperationalMetrics> rows = jdbcTemplate.query(sql, parameters, ROW_MAPPER);
        Map<UUID, SellerOperationalMetrics> metrics = new HashMap<>();
        rows.forEach(row -> metrics.put(row.sellerId(), row));
        return metrics;
    }

    public SellerOperationalMetrics findForSeller(UUID sellerId) {
        String sql = METRICS_SELECT + " WHERE s.id = :sellerId GROUP BY s.id";
        return jdbcTemplate.queryForObject(sql, Map.of("sellerId", sellerId), ROW_MAPPER);
    }

    public SoldCommissionMetrics findCommission(UUID sellerId, Instant fromInclusive, Instant toExclusive) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) AS sold_count,
                    COALESCE(SUM(ci.value_cents_snapshot), 0) AS sold_value_cents,
                    COALESCE(SUM(ROUND(ci.value_cents_snapshot::numeric * ci.commission_rate_snapshot / 100)), 0)
                        AS commission_cents
                FROM consignment_items ci
                JOIN consignments c ON c.id = ci.consignment_id
                WHERE c.seller_id = :sellerId
                  AND ci.status = 'VENDIDA'
                  AND ci.sold_at >= :fromInclusive
                  AND ci.sold_at < :toExclusive
                """, Map.of("sellerId", sellerId,
                    "fromInclusive", Timestamp.from(fromInclusive),
                    "toExclusive", Timestamp.from(toExclusive)),
                (resultSet, rowNumber) -> new SoldCommissionMetrics(
                        resultSet.getInt("sold_count"), resultSet.getLong("sold_value_cents"),
                        resultSet.getLong("commission_cents")));
    }

    public List<SoldItem> findSoldItems(UUID sellerId, Instant fromInclusive, Instant toExclusive) {
        return jdbcTemplate.query("""
                SELECT ci.sold_at, ci.value_cents_snapshot, ci.commission_rate_snapshot, c.payment_term_days
                FROM consignment_items ci
                JOIN consignments c ON c.id = ci.consignment_id
                WHERE c.seller_id = :sellerId
                  AND ci.status = 'VENDIDA'
                  AND ci.sold_at >= :fromInclusive
                  AND ci.sold_at < :toExclusive
                ORDER BY ci.sold_at
                """, Map.of("sellerId", sellerId,
                    "fromInclusive", Timestamp.from(fromInclusive),
                    "toExclusive", Timestamp.from(toExclusive)),
                (resultSet, rowNumber) -> new SoldItem(resultSet.getTimestamp("sold_at").toInstant(),
                        resultSet.getLong("value_cents_snapshot"), resultSet.getInt("commission_rate_snapshot"),
                        resultSet.getObject("payment_term_days", Integer.class)));
    }

    private static SellerOperationalMetrics mapRow(ResultSet resultSet, int rowNumber) throws SQLException {
        java.sql.Timestamp nextDue = resultSet.getTimestamp("next_due_at");
        return new SellerOperationalMetrics(
                resultSet.getObject("seller_id", UUID.class),
                resultSet.getLong("pieces_in_hand"),
                resultSet.getLong("value_in_hand_cents"),
                nextDue == null ? null : nextDue.toInstant(),
                resultSet.getInt("open_lots"),
                resultSet.getInt("total_sold_count"),
                resultSet.getLong("total_sold_value_cents"),
                resultSet.getLong("total_commission_cents"));
    }

    public record SellerOperationalMetrics(UUID sellerId, long piecesInHand, long valueInHandCents,
            Instant nextDueAt, int openLots, int totalSoldCount, long totalSoldValueCents,
            long totalCommissionCents) {
        public static SellerOperationalMetrics empty(UUID sellerId) {
            return new SellerOperationalMetrics(sellerId, 0, 0, null, 0, 0, 0, 0);
        }
    }

    public record SoldCommissionMetrics(int soldCount, long soldValueCents, long commissionCents) {
    }

    public record SoldItem(Instant soldAt, long valueCents, int commissionRate, Integer paymentTermDays) {
    }
}