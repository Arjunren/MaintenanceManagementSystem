package com.arjunren.maintenance;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CoreTest {
    Db db;
    Core service;

    @BeforeEach
    void initialize() throws Exception {
        db = new Db("jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        db.migrate();
        try (var connection = db.connection()) {
            connection.createStatement().execute(
                    "INSERT INTO users(username,password_hash,role,full_name) VALUES" +
                            "('admin','x','ADMIN','Admin'),('tech','x','TECHNICIAN','Technician')," +
                            "('other','x','TECHNICIAN','Other'),('requester','x','REQUESTER','Requester')");
            connection.createStatement().execute(
                    "INSERT INTO assets(code,name,location,status) VALUES" +
                            "('A1','Generator','Plant','ACTIVE'),('A2','Old Pump','Store','RETIRED')");
        }
        service = new Core(db);
    }

    @Test
    void requesterCreatesWorkOrder() {
        var order = service.create(1, 4, "Generator fault", "Generator stops under heavy load.", "HIGH");
        assertEquals("OPEN", order.status());
    }

    @Test
    void retiredAssetRejectsWorkOrder() {
        assertThrows(IllegalStateException.class,
                () -> service.create(2, 4, "Pump fault", "The retired pump is not operational.", "LOW"));
    }

    @Test
    void assignmentReservesAsset() throws Exception {
        var order = service.create(1, 4, "Generator fault", "Generator stops under heavy load.", "CRITICAL");
        assertEquals("ASSIGNED", service.assign(order.id(), 2).status());
        try (var connection = db.connection();
             var result = connection.createStatement().executeQuery("SELECT status FROM assets WHERE id=1")) {
            result.next();
            assertEquals("UNDER_MAINTENANCE", result.getString(1));
        }
    }

    @Test
    void technicianOwnershipAndLifecycleAreEnforced() throws Exception {
        var order = service.create(1, 4, "Generator fault", "Generator stops under heavy load.", "MEDIUM");
        service.assign(order.id(), 2);
        assertThrows(SecurityException.class,
                () -> service.transition(order.id(), "IN_PROGRESS", 3, "TECHNICIAN"));
        assertEquals("IN_PROGRESS", service.transition(order.id(), "IN_PROGRESS", 2, "TECHNICIAN").status());
        service.addLog(order.id(), 2, "TECHNICIAN", "Replaced the voltage regulator.", 2.5);
        assertEquals("COMPLETED", service.transition(order.id(), "COMPLETED", 2, "TECHNICIAN").status());
        try (var connection = db.connection();
             var result = connection.createStatement().executeQuery("SELECT status FROM assets WHERE id=1")) {
            result.next();
            assertEquals("ACTIVE", result.getString(1));
        }
    }
}
