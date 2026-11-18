package com.nocook.service.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;

/** R11: the application datasource is nocook_app; it must have no privileges on schema oms (CLAUDE.md rule 1). */
class NocookRoleCannotWriteOmsIT extends AbstractIT {

    @BeforeAll
    static void createOmsTable() {
        asSuperuser("create table if not exists oms.orders (id text primary key, status text)");
        asSuperuser("alter table oms.orders owner to oms_app");
        asSuperuser("insert into oms.orders values ('ord-r11', 'ACCEPTED') on conflict do nothing");
    }

    @Test
    void nocook_app_cannot_read_or_update_oms_orders() {
        String role = jdbc.sql("select current_user").query(String.class).single();

        Throwable readFailure = catchThrowable(() -> jdbc.sql("select 1 from oms.orders").query().listOfRows());
        Throwable writeFailure = catchThrowable(
                () -> jdbc.sql("update oms.orders set status = 'CANCELLED' where id = 'ord-r11'").update());

        boolean passed = role.equals("nocook_app") && readFailure != null && writeFailure != null
                && rootMessage(readFailure).contains("permission denied");
        ItSummary.print("R11", Map.of("role", role, "readDenied", readFailure != null,
                "writeDenied", writeFailure != null), passed);

        assertThat(role).isEqualTo("nocook_app");
        assertThat(readFailure).isInstanceOf(DataAccessException.class);
        assertThat(rootMessage(readFailure)).contains("permission denied");
        assertThat(writeFailure).isInstanceOf(DataAccessException.class);
        assertThat(rootMessage(writeFailure)).contains("permission denied");
    }

    private static String rootMessage(Throwable failure) {
        Throwable root = failure;
        while (root.getCause() != null) {
            root = root.getCause();
        }
        return root.getMessage();
    }

    private static Throwable catchThrowable(Runnable action) {
        try {
            action.run();
            return null;
        } catch (RuntimeException ex) {
            return ex;
        }
    }
}
