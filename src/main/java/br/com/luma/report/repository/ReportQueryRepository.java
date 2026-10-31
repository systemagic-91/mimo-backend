package br.com.luma.report.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import br.com.luma.shared.PieceStage;

@Repository
public class ReportQueryRepository {

    private static final String LAST_LABEL_BEFORE_SALE = """
            LEFT JOIN LATERAL (
                SELECT MAX(e.occurred_at) AS labeled_at
                FROM piece_events e
                WHERE e.piece_id = ci.piece_id AND e.stage = 'ETIQUETADA' AND e.occurred_at <= ci.sold_at
            ) label ON TRUE
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public ReportQueryRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<SaleRow> findSales(Instant fromInclusive, Instant toExclusive) {
        return jdbcTemplate.query("""
                SELECT ci.code_snapshot, ci.type_snapshot, ci.value_cents_snapshot, ci.sold_at,
                    ci.commission_rate_snapshot, p.factory_cost_cents, p.bath_cost_cents,
                    s.id AS seller_id, s.name AS seller_name,
                    COALESCE(label.labeled_at, p.created_at) AS labeled_at
                FROM consignment_items ci
                JOIN consignments c ON c.id = ci.consignment_id
                JOIN sellers s ON s.id = c.seller_id
                JOIN pieces p ON p.id = ci.piece_id
                """ + LAST_LABEL_BEFORE_SALE + """
                WHERE ci.status = 'VENDIDA'
                  AND ci.sold_at >= :fromInclusive
                  AND ci.sold_at < :toExclusive
                ORDER BY ci.sold_at, ci.code_snapshot
                """, Map.of("fromInclusive", Timestamp.from(fromInclusive),
                "toExclusive", Timestamp.from(toExclusive)),
                (resultSet, rowNumber) -> new SaleRow(resultSet.getString("code_snapshot"),
                        resultSet.getString("type_snapshot"), resultSet.getLong("value_cents_snapshot"),
                        instant(resultSet, "sold_at"), resultSet.getObject("seller_id", UUID.class),
                        resultSet.getString("seller_name"), instant(resultSet, "labeled_at"),
                        resultSet.getInt("commission_rate_snapshot"),
                        resultSet.getObject("factory_cost_cents", Long.class),
                        resultSet.getObject("bath_cost_cents", Long.class)));
    }

    public List<StockRow> findLabeledPiecesLabeledBefore(Instant labeledBefore) {
        return jdbcTemplate.query("""
                SELECT stock.id, stock.code, stock.type, stock.value_cents, stock.labeled_at
                FROM (
                    SELECT p.id, p.code, p.type, p.value_cents,
                        COALESCE((SELECT MAX(e.occurred_at) FROM piece_events e
                                  WHERE e.piece_id = p.id AND e.stage = 'ETIQUETADA'), p.created_at) AS labeled_at
                    FROM pieces p
                    WHERE p.status = 'ETIQUETADA'
                ) stock
                WHERE stock.labeled_at < :labeledBefore
                ORDER BY stock.labeled_at, stock.code
                """, Map.of("labeledBefore", Timestamp.from(labeledBefore)),
                (resultSet, rowNumber) -> new StockRow(resultSet.getObject("id", UUID.class),
                        resultSet.getString("code"), resultSet.getString("type"), resultSet.getLong("value_cents"),
                        instant(resultSet, "labeled_at")));
    }

    public List<OverdueSellerRow> findOverdueSellers(Instant now) {
        return jdbcTemplate.query("""
                SELECT s.id AS seller_id, s.name, s.phone,
                    COUNT(DISTINCT c.id) AS late_lots,
                    COUNT(ci.id) FILTER (WHERE ci.status = 'COM_VENDEDORA') AS pieces_in_hand,
                    COALESCE(SUM(ci.value_cents_snapshot) FILTER (WHERE ci.status = 'COM_VENDEDORA'), 0)
                        AS value_at_risk_cents,
                    MIN(c.due_at) AS oldest_due_at
                FROM consignments c
                JOIN sellers s ON s.id = c.seller_id
                LEFT JOIN consignment_items ci ON ci.consignment_id = c.id
                WHERE c.status = 'ABERTA' AND c.due_at < :now
                GROUP BY s.id, s.name, s.phone
                ORDER BY MIN(c.due_at), s.name
                """, Map.of("now", Timestamp.from(now)),
                (resultSet, rowNumber) -> new OverdueSellerRow(resultSet.getObject("seller_id", UUID.class),
                        resultSet.getString("name"), resultSet.getString("phone"), resultSet.getInt("late_lots"),
                        resultSet.getLong("pieces_in_hand"), resultSet.getLong("value_at_risk_cents"),
                        instant(resultSet, "oldest_due_at")));
    }

    public Map<PieceStage, Long> countPiecesByStage() {
        Map<PieceStage, Long> counts = new EnumMap<>(PieceStage.class);
        jdbcTemplate.query("SELECT status, COUNT(*) AS total FROM pieces GROUP BY status", Map.of(),
                resultSet -> {
                    counts.put(PieceStage.valueOf(resultSet.getString("status")), resultSet.getLong("total"));
                });
        return counts;
    }

    public InHandTotals findInHandTotals() {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(ci.id) AS pieces, COALESCE(SUM(ci.value_cents_snapshot), 0) AS value_cents
                FROM consignment_items ci
                JOIN consignments c ON c.id = ci.consignment_id
                WHERE ci.status = 'COM_VENDEDORA' AND c.status = 'ABERTA'
                """, Map.of(), (resultSet, rowNumber) -> new InHandTotals(resultSet.getLong("pieces"),
                resultSet.getLong("value_cents")));
    }

    private static Instant instant(ResultSet resultSet, String column) throws SQLException {
        Timestamp value = resultSet.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record SaleRow(String code, String type, long valueCents, Instant soldAt, UUID sellerId,
            String sellerName, Instant labeledAt, int commissionRate, Long factoryCostCents, Long bathCostCents) {
    }

    public record StockRow(UUID id, String code, String type, long valueCents, Instant labeledAt) {
    }

    public record OverdueSellerRow(UUID sellerId, String sellerName, String phone, int lateLots, long piecesInHand,
            long valueAtRiskCents, Instant oldestDueAt) {
    }

    public record InHandTotals(long pieces, long valueCents) {
    }
}
