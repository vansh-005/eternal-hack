package com.nocook.oms.orders;

import com.nocook.oms.orders.OrderModel.CancelInitiator;
import com.nocook.oms.orders.OrderModel.OrderStatus;
import com.nocook.oms.orders.OrderModel.Stage;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

@Repository
public class OrderRepository {

    private static final String COLUMNS = """
            id, outlet_id, version, status, stage, value_paise, order_ref, items,
            accepted_at, ready_at, picked_up_at, delivered_at, cancelled_at, cancel_initiator, run_id
            """;

    /** Changes applied by one lifecycle mutation. Null timestamps keep the stored value. */
    public record Mutation(
            OrderStatus status,
            Stage stage,
            @Nullable Instant acceptedAt,
            @Nullable Instant readyAt,
            @Nullable Instant pickedUpAt,
            @Nullable Instant deliveredAt,
            @Nullable Instant cancelledAt,
            @Nullable CancelInitiator cancelInitiator,
            boolean clearCancellation) {}

    private final JdbcClient jdbc;
    private final JsonMapper jsonMapper;
    private final RowMapper<OrderSnapshot> snapshotMapper = this::mapSnapshot;

    public OrderRepository(JdbcClient jdbc, JsonMapper jsonMapper) {
        this.jdbc = jdbc;
        this.jsonMapper = jsonMapper;
    }

    public OrderSnapshot insert(
            String outletId,
            OrderStatus status,
            Stage stage,
            long valuePaise,
            String itemsJson,
            @Nullable String runId,
            @Nullable Instant acceptedAt,
            Instant now) {
        String sql = """
                with next_seq as (select nextval('oms.order_seq') as seq)
                insert into oms.orders (id, outlet_id, status, stage, value_paise, order_ref, items,
                                        accepted_at, run_id, created_at, updated_at)
                select 'ord-' || lpad(next_seq.seq::text, 8, '0'), :outletId, :status, :stage, :valuePaise,
                       right(lpad(next_seq.seq::text, 8, '0'), 4), cast(:items as jsonb),
                       cast(:acceptedAt as timestamptz), :runId, :now, :now
                from next_seq
                returning\s""" + COLUMNS;
        return jdbc.sql(sql)
                .param("outletId", outletId)
                .param("status", status.name())
                .param("stage", stage.name())
                .param("valuePaise", valuePaise)
                .param("items", itemsJson)
                .param("acceptedAt", toOffset(acceptedAt))
                .param("runId", runId)
                .param("now", toOffset(now))
                .query(snapshotMapper)
                .single();
    }

    public Optional<OrderSnapshot> findById(String orderId) {
        return jdbc.sql("select " + COLUMNS + " from oms.orders where id = :id")
                .param("id", orderId)
                .query(snapshotMapper)
                .optional();
    }

    public Optional<OrderSnapshot> findByIdForUpdate(String orderId) {
        return jdbc.sql("select " + COLUMNS + " from oms.orders where id = :id for update")
                .param("id", orderId)
                .query(snapshotMapper)
                .optional();
    }

    public OrderSnapshot apply(String orderId, Mutation mutation, Instant now) {
        String sql = """
                update oms.orders
                set status = :status,
                    stage = :stage,
                    version = version + 1,
                    updated_at = :now,
                    accepted_at = coalesce(cast(:acceptedAt as timestamptz), accepted_at),
                    ready_at = coalesce(cast(:readyAt as timestamptz), ready_at),
                    picked_up_at = coalesce(cast(:pickedUpAt as timestamptz), picked_up_at),
                    delivered_at = coalesce(cast(:deliveredAt as timestamptz), delivered_at),
                    cancelled_at = case when :clearCancellation then null
                                        else coalesce(cast(:cancelledAt as timestamptz), cancelled_at) end,
                    cancel_initiator = case when :clearCancellation then null
                                            else coalesce(cast(:cancelInitiator as text), cancel_initiator) end
                where id = :id
                returning\s""" + COLUMNS;
        return jdbc.sql(sql)
                .param("id", orderId)
                .param("status", mutation.status().name())
                .param("stage", mutation.stage().name())
                .param("now", toOffset(now))
                .param("acceptedAt", toOffset(mutation.acceptedAt()))
                .param("readyAt", toOffset(mutation.readyAt()))
                .param("pickedUpAt", toOffset(mutation.pickedUpAt()))
                .param("deliveredAt", toOffset(mutation.deliveredAt()))
                .param("cancelledAt", toOffset(mutation.cancelledAt()))
                .param("cancelInitiator", mutation.cancelInitiator() == null ? null : mutation.cancelInitiator().name())
                .param("clearCancellation", mutation.clearCancellation())
                .query(snapshotMapper)
                .single();
    }

    public List<OrderSnapshot> listForOutlet(String outletId, boolean activeOnly, int limit) {
        String activeFilter = activeOnly ? " and status not in ('DELIVERED','CANCELLED')" : "";
        String sql = "select " + COLUMNS + " from oms.orders where outlet_id = :outletId" + activeFilter
                + " order by updated_at desc, id desc limit :limit";
        return jdbc.sql(sql)
                .param("outletId", outletId)
                .param("limit", limit)
                .query(snapshotMapper)
                .list();
    }

    private OrderSnapshot mapSnapshot(ResultSet rs, int rowNumber) throws SQLException {
        String cancelInitiator = rs.getString("cancel_initiator");
        return new OrderSnapshot(
                rs.getString("id"),
                rs.getString("outlet_id"),
                rs.getInt("version"),
                OrderStatus.valueOf(rs.getString("status")),
                Stage.valueOf(rs.getString("stage")),
                rs.getLong("value_paise"),
                rs.getString("order_ref"),
                toInstant(rs, "accepted_at"),
                toInstant(rs, "ready_at"),
                toInstant(rs, "picked_up_at"),
                toInstant(rs, "delivered_at"),
                toInstant(rs, "cancelled_at"),
                cancelInitiator == null ? null : CancelInitiator.valueOf(cancelInitiator),
                jsonMapper.readTree(rs.getString("items")),
                rs.getString("run_id"));
    }

    private static @Nullable Instant toInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static @Nullable OffsetDateTime toOffset(@Nullable Instant instant) {
        return instant == null ? null : OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
