package com.arjunren.maintenance;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

final class Db {
    private final String url;

    Db(String url) {
        this.url = url;
    }

    Connection connection() throws SQLException {
        return DriverManager.getConnection(url, "sa", "");
    }

    void migrate() {
        try (var input = getClass().getResourceAsStream("/db/migration.sql");
             var connection = connection();
             var statement = connection.createStatement()) {
            if (input == null) throw new IllegalStateException("Database migration is missing");
            for (var sql : new String(input.readAllBytes(), StandardCharsets.UTF_8).split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }
}

final class Auth {
    private final Db db;

    Auth(Db db) {
        this.db = db;
    }

    String password(String password) {
        var salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt) + ":" + derive(password, salt);
    }

    private String derive(String password, byte[] salt) {
        try {
            var spec = new PBEKeySpec(password.toCharArray(), salt, 210_000, 256);
            return Base64.getEncoder().encodeToString(
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded());
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    Session login(String username, String password) {
        try (var connection = db.connection();
             var statement = connection.prepareStatement(
                     "SELECT id,password_hash,role FROM users WHERE username=?")) {
            statement.setString(1, username);
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SecurityException();
                var hash = result.getString(2).split(":");
                var expected = Base64.getDecoder().decode(hash[1]);
                var actual = Base64.getDecoder().decode(derive(password, Base64.getDecoder().decode(hash[0])));
                if (!MessageDigest.isEqual(expected, actual)) throw new SecurityException();
                var token = UUID.randomUUID().toString() + UUID.randomUUID();
                try (var insert = connection.prepareStatement("INSERT INTO sessions VALUES(?,?,?)")) {
                    insert.setString(1, digest(token));
                    insert.setLong(2, result.getLong(1));
                    insert.setObject(3, OffsetDateTime.now().plusHours(2));
                    insert.executeUpdate();
                }
                return new Session(token, result.getLong(1), result.getString(3));
            }
        } catch (SecurityException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    Principal require(String authorization, String... roles) {
        if (authorization == null || !authorization.startsWith("Bearer ")) throw new SecurityException();
        try (var connection = db.connection();
             var statement = connection.prepareStatement(
                     "SELECT u.id,u.role FROM sessions s JOIN users u ON u.id=s.user_id " +
                             "WHERE s.token_hash=? AND s.expires_at>CURRENT_TIMESTAMP")) {
            statement.setString(1, digest(authorization.substring(7)));
            try (var result = statement.executeQuery()) {
                if (!result.next()) throw new SecurityException();
                var principal = new Principal(result.getLong(1), result.getString(2));
                if (roles.length > 0 && Arrays.stream(roles).noneMatch(principal.role()::equals)) {
                    throw new SecurityException();
                }
                return principal;
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    private String digest(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    record Session(String token, long id, String role) {}
    record Principal(long id, String role) {}
}

public final class Core {
    private static final Set<String> PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private final Db db;

    Core(Db db) {
        this.db = db;
    }

    public WorkOrder create(long assetId, long requesterId, String title, String description, String priority) {
        if (title == null || title.isBlank() || description == null || description.length() < 10) {
            throw new IllegalArgumentException("A title and detailed description are required");
        }
        if (!PRIORITIES.contains(priority)) throw new IllegalArgumentException("Invalid priority");
        try (var connection = db.connection()) {
            connection.setAutoCommit(false);
            try {
                try (var asset = connection.prepareStatement("SELECT status FROM assets WHERE id=? FOR UPDATE")) {
                    asset.setLong(1, assetId);
                    try (var result = asset.executeQuery()) {
                        if (!result.next()) throw new IllegalArgumentException("Asset not found");
                        if (result.getString(1).equals("RETIRED")) {
                            throw new IllegalStateException("Retired assets cannot receive work orders");
                        }
                    }
                }
                var reference = "WO-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
                try (var insert = connection.prepareStatement(
                        "INSERT INTO work_orders(reference,asset_id,requester_id,title,description,priority) " +
                                "VALUES(?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
                    insert.setString(1, reference);
                    insert.setLong(2, assetId);
                    insert.setLong(3, requesterId);
                    insert.setString(4, title.trim());
                    insert.setString(5, description.trim());
                    insert.setString(6, priority);
                    insert.executeUpdate();
                    try (var keys = insert.getGeneratedKeys()) {
                        keys.next();
                        connection.commit();
                        return new WorkOrder(keys.getLong(1), reference, "OPEN");
                    }
                }
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    public WorkOrder assign(long workOrderId, long technicianId) {
        try (var connection = db.connection()) {
            connection.setAutoCommit(false);
            try {
                try (var technician = connection.prepareStatement("SELECT role FROM users WHERE id=?")) {
                    technician.setLong(1, technicianId);
                    try (var result = technician.executeQuery()) {
                        if (!result.next() || !result.getString(1).equals("TECHNICIAN")) {
                            throw new IllegalArgumentException("Technician not found");
                        }
                    }
                }
                long assetId;
                String reference;
                try (var select = connection.prepareStatement(
                        "SELECT reference,status,asset_id FROM work_orders WHERE id=? FOR UPDATE")) {
                    select.setLong(1, workOrderId);
                    try (var result = select.executeQuery()) {
                        if (!result.next()) throw new IllegalArgumentException("Work order not found");
                        if (!result.getString(2).equals("OPEN")) {
                            throw new IllegalStateException("Only open work orders can be assigned");
                        }
                        reference = result.getString(1);
                        assetId = result.getLong(3);
                    }
                }
                try (var update = connection.prepareStatement(
                        "UPDATE work_orders SET assigned_to=?,status='ASSIGNED',updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
                    update.setLong(1, technicianId);
                    update.setLong(2, workOrderId);
                    update.executeUpdate();
                }
                try (var update = connection.prepareStatement(
                        "UPDATE assets SET status='UNDER_MAINTENANCE' WHERE id=?")) {
                    update.setLong(1, assetId);
                    update.executeUpdate();
                }
                connection.commit();
                return new WorkOrder(workOrderId, reference, "ASSIGNED");
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    public WorkOrder transition(long workOrderId, String next, long actorId, String actorRole) {
        try (var connection = db.connection()) {
            connection.setAutoCommit(false);
            try {
                String current;
                String reference;
                long assetId;
                Long assignedTo;
                try (var select = connection.prepareStatement(
                        "SELECT reference,status,asset_id,assigned_to FROM work_orders WHERE id=? FOR UPDATE")) {
                    select.setLong(1, workOrderId);
                    try (var result = select.executeQuery()) {
                        if (!result.next()) throw new IllegalArgumentException("Work order not found");
                        reference = result.getString(1);
                        current = result.getString(2);
                        assetId = result.getLong(3);
                        assignedTo = (Long) result.getObject(4);
                    }
                }
                if (actorRole.equals("TECHNICIAN") && !Long.valueOf(actorId).equals(assignedTo)) {
                    throw new SecurityException("Work order is assigned to another technician");
                }
                var allowed = switch (current) {
                    case "OPEN" -> Set.of("CANCELLED");
                    case "ASSIGNED" -> Set.of("IN_PROGRESS", "CANCELLED");
                    case "IN_PROGRESS" -> Set.of("COMPLETED", "CANCELLED");
                    default -> Set.<String>of();
                };
                if (!allowed.contains(next)) throw new IllegalStateException("Invalid work-order transition");
                try (var update = connection.prepareStatement(
                        "UPDATE work_orders SET status=?,updated_at=CURRENT_TIMESTAMP WHERE id=?")) {
                    update.setString(1, next);
                    update.setLong(2, workOrderId);
                    update.executeUpdate();
                }
                if (next.equals("COMPLETED") || next.equals("CANCELLED")) {
                    try (var update = connection.prepareStatement("UPDATE assets SET status='ACTIVE' WHERE id=?")) {
                        update.setLong(1, assetId);
                        update.executeUpdate();
                    }
                }
                connection.commit();
                return new WorkOrder(workOrderId, reference, next);
            } catch (RuntimeException | SQLException error) {
                connection.rollback();
                throw error;
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    public long addLog(long workOrderId, long actorId, String actorRole, String notes, double hours) {
        if (notes == null || notes.length() < 5 || hours <= 0 || hours > 24) {
            throw new IllegalArgumentException("Valid notes and hours between 0 and 24 are required");
        }
        try (var connection = db.connection()) {
            Long assignedTo;
            String status;
            try (var select = connection.prepareStatement(
                    "SELECT assigned_to,status FROM work_orders WHERE id=?")) {
                select.setLong(1, workOrderId);
                try (var result = select.executeQuery()) {
                    if (!result.next()) throw new IllegalArgumentException("Work order not found");
                    assignedTo = (Long) result.getObject(1);
                    status = result.getString(2);
                }
            }
            if (actorRole.equals("TECHNICIAN") && !Long.valueOf(actorId).equals(assignedTo)) {
                throw new SecurityException("Work order is assigned to another technician");
            }
            if (!Set.of("ASSIGNED", "IN_PROGRESS").contains(status)) {
                throw new IllegalStateException("Logs can only be added to active work orders");
            }
            try (var insert = connection.prepareStatement(
                    "INSERT INTO maintenance_logs(work_order_id,performed_by,notes,hours) VALUES(?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.setLong(1, workOrderId);
                insert.setLong(2, actorId);
                insert.setString(3, notes.trim());
                insert.setDouble(4, hours);
                insert.executeUpdate();
                try (var keys = insert.getGeneratedKeys()) {
                    keys.next();
                    return keys.getLong(1);
                }
            }
        } catch (SQLException error) {
            throw new IllegalStateException(error);
        }
    }

    public record WorkOrder(long id, String reference, String status) {}
}
