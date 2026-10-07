package com.arjunren.maintenance;

import io.javalin.Javalin;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class Main {
    public static void main(String[] args) {
        var db = new Db(System.getenv().getOrDefault(
                "DB_URL", "jdbc:h2:file:./data/maintenance;AUTO_SERVER=TRUE"));
        db.migrate();
        var auth = new Auth(db);
        seed(db, auth);
        var service = new Core(db);

        Javalin.create(config -> {
            var routes = config.routes;
            routes.exception(SecurityException.class, (error, ctx) ->
                    ctx.status(403).json(error("AUTH_ERROR", "Unauthorized")));
            routes.exception(IllegalArgumentException.class, (error, ctx) ->
                    ctx.status(400).json(error("VALIDATION_ERROR", error.getMessage())));
            routes.exception(IllegalStateException.class, (error, ctx) ->
                    ctx.status(409).json(error("CONFLICT", error.getMessage())));

            routes.post("/api/auth/login", ctx -> {
                var request = ctx.bodyAsClass(Login.class);
                ctx.json(Map.of("data", auth.login(request.username, request.password)));
            });
            routes.get("/api/assets", ctx -> {
                auth.require(ctx.header("Authorization"));
                try (var connection = db.connection();
                     var statement = connection.prepareStatement("SELECT * FROM assets ORDER BY code")) {
                    ctx.json(Map.of("data", rows(statement)));
                }
            });
            routes.post("/api/assets", ctx -> {
                auth.require(ctx.header("Authorization"), "ADMIN");
                var request = ctx.bodyAsClass(AssetRequest.class);
                if (request.code == null || request.code.isBlank() || request.name == null || request.name.isBlank()
                        || request.location == null || request.location.isBlank()) {
                    throw new IllegalArgumentException("Code, name, and location are required");
                }
                try (var connection = db.connection();
                     var statement = connection.prepareStatement(
                             "INSERT INTO assets(code,name,location) VALUES(?,?,?)",
                             java.sql.Statement.RETURN_GENERATED_KEYS)) {
                    statement.setString(1, request.code.trim());
                    statement.setString(2, request.name.trim());
                    statement.setString(3, request.location.trim());
                    statement.executeUpdate();
                    try (var keys = statement.getGeneratedKeys()) {
                        keys.next();
                        ctx.status(201).json(Map.of("data", Map.of("id", keys.getLong(1))));
                    }
                }
            });
            routes.post("/api/work-orders", ctx -> {
                var principal = auth.require(ctx.header("Authorization"), "ADMIN", "REQUESTER");
                var request = ctx.bodyAsClass(WorkOrderRequest.class);
                var requesterId = principal.role().equals("REQUESTER") ? principal.id() : request.requesterId;
                ctx.status(201).json(Map.of("data", service.create(request.assetId, requesterId,
                        request.title, request.description, request.priority)));
            });
            routes.get("/api/work-orders", ctx -> {
                var principal = auth.require(ctx.header("Authorization"), "ADMIN", "TECHNICIAN", "REQUESTER");
                var filter = switch (principal.role()) {
                    case "REQUESTER" -> " WHERE w.requester_id=?";
                    case "TECHNICIAN" -> " WHERE w.assigned_to=?";
                    default -> "";
                };
                var sql = "SELECT w.*,a.code asset_code,a.name asset_name,u.full_name requester_name " +
                        "FROM work_orders w JOIN assets a ON a.id=w.asset_id " +
                        "JOIN users u ON u.id=w.requester_id" + filter + " ORDER BY w.created_at DESC";
                try (var connection = db.connection(); var statement = connection.prepareStatement(sql)) {
                    if (!filter.isEmpty()) statement.setLong(1, principal.id());
                    ctx.json(Map.of("data", rows(statement)));
                }
            });
            routes.patch("/api/work-orders/{id}/assignment", ctx -> {
                auth.require(ctx.header("Authorization"), "ADMIN");
                var request = ctx.bodyAsClass(AssignmentRequest.class);
                ctx.json(Map.of("data", service.assign(
                        Long.parseLong(ctx.pathParam("id")), request.technicianId)));
            });
            routes.patch("/api/work-orders/{id}/status", ctx -> {
                var principal = auth.require(ctx.header("Authorization"), "ADMIN", "TECHNICIAN");
                var request = ctx.bodyAsClass(StatusRequest.class);
                ctx.json(Map.of("data", service.transition(Long.parseLong(ctx.pathParam("id")),
                        request.status, principal.id(), principal.role())));
            });
            routes.post("/api/work-orders/{id}/logs", ctx -> {
                var principal = auth.require(ctx.header("Authorization"), "ADMIN", "TECHNICIAN");
                var request = ctx.bodyAsClass(LogRequest.class);
                var id = service.addLog(Long.parseLong(ctx.pathParam("id")), principal.id(),
                        principal.role(), request.notes, request.hours);
                ctx.status(201).json(Map.of("data", Map.of("id", id)));
            });
            routes.get("/api/work-orders/{id}/logs", ctx -> {
                var principal = auth.require(ctx.header("Authorization"), "ADMIN", "TECHNICIAN", "REQUESTER");
                var workOrderId = Long.parseLong(ctx.pathParam("id"));
                assertCanView(db, principal, workOrderId);
                try (var connection = db.connection(); var statement = connection.prepareStatement(
                        "SELECT l.*,u.full_name performed_by_name FROM maintenance_logs l " +
                                "JOIN users u ON u.id=l.performed_by WHERE l.work_order_id=? ORDER BY l.created_at")) {
                    statement.setLong(1, workOrderId);
                    ctx.json(Map.of("data", rows(statement)));
                }
            });
            routes.get("/api/dashboard", ctx -> {
                auth.require(ctx.header("Authorization"), "ADMIN");
                try (var connection = db.connection(); var statement = connection.prepareStatement(
                        "SELECT COUNT(*) total," +
                                "COUNT(CASE WHEN status='OPEN' THEN 1 END) open_orders," +
                                "COUNT(CASE WHEN status='IN_PROGRESS' THEN 1 END) in_progress," +
                                "COUNT(CASE WHEN status='COMPLETED' THEN 1 END) completed FROM work_orders")) {
                    ctx.json(Map.of("data", rows(statement).getFirst()));
                }
            });
        }).start(Integer.parseInt(System.getenv().getOrDefault("PORT", "8080")));
    }

    private static void assertCanView(Db db, Auth.Principal principal, long workOrderId) throws Exception {
        if (principal.role().equals("ADMIN")) return;
        var column = principal.role().equals("REQUESTER") ? "requester_id" : "assigned_to";
        try (var connection = db.connection();
             var statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM work_orders WHERE id=? AND " + column + "=?")) {
            statement.setLong(1, workOrderId);
            statement.setLong(2, principal.id());
            try (var result = statement.executeQuery()) {
                result.next();
                if (result.getInt(1) == 0) throw new SecurityException();
            }
        }
    }

    static List<Map<String, Object>> rows(PreparedStatement statement) throws Exception {
        try (var results = statement.executeQuery()) {
            var output = new ArrayList<Map<String, Object>>();
            var metadata = results.getMetaData();
            while (results.next()) {
                var row = new LinkedHashMap<String, Object>();
                for (int index = 1; index <= metadata.getColumnCount(); index++) {
                    row.put(metadata.getColumnLabel(index).toLowerCase(), results.getObject(index));
                }
                output.add(row);
            }
            return output;
        }
    }

    static Map<String, Object> error(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }

    static void seed(Db db, Auth auth) {
        try (var connection = db.connection();
             var count = connection.createStatement().executeQuery("SELECT COUNT(*) FROM users")) {
            count.next();
            if (count.getInt(1) > 0) return;
            try (var statement = connection.prepareStatement(
                    "INSERT INTO users(username,password_hash,role,full_name) VALUES(?,?,?,?)")) {
                for (var user : new String[][]{
                        {"admin", "ADMIN", "System Administrator"},
                        {"technician", "TECHNICIAN", "Demo Technician"},
                        {"requester", "REQUESTER", "Demo Requester"}}) {
                    statement.setString(1, user[0]);
                    statement.setString(2, auth.password("ChangeMe123!"));
                    statement.setString(3, user[1]);
                    statement.setString(4, user[2]);
                    statement.addBatch();
                }
                statement.executeBatch();
            }
            connection.createStatement().execute(
                    "INSERT INTO assets(code,name,location) VALUES('ASSET-001','Backup Generator','Building A')");
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    record Login(String username, String password) {}
    record AssetRequest(String code, String name, String location) {}
    record WorkOrderRequest(long assetId, long requesterId, String title, String description, String priority) {}
    record AssignmentRequest(long technicianId) {}
    record StatusRequest(String status) {}
    record LogRequest(String notes, double hours) {}
}
