package dao;

import database.DatabaseConnection;
import model.Location;
import model.LocationType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * LocationDAO (Data Access Object for the "locations" table).
 * Only this class contains SQL about locations; the rest of the program just
 * calls its Java methods. Every query uses a PreparedStatement.
 * Phase 10 will add insert / update / deactivate methods here.
 */
public class LocationDAO {

    // The columns we read, in one place so every query returns the same shape.
    private static final String SELECT_COLUMNS =
            "SELECT location_id, location_name, location_type, description, "
          + "x_coordinate, y_coordinate, is_active FROM locations ";

    /** Returns ALL locations (active and inactive), ordered by id. */
    public List<Location> findAll() throws SQLException {
        List<Location> result = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(SELECT_COLUMNS + "ORDER BY location_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        }
        return result;
    }

    /** Returns the location with this id, or null if there is none. */
    public Location findById(int id) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(SELECT_COLUMNS + "WHERE location_id = ?")) {
            ps.setInt(1, id);                        // the ? is replaced safely by the id
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    /** Turns the current row of the ResultSet into a Location object. */
    private Location mapRow(ResultSet rs) throws SQLException {
        try {
            return new Location(
                    rs.getInt("location_id"),
                    rs.getString("location_name"),
                    LocationType.parse(rs.getString("location_type")),
                    rs.getString("description"),
                    rs.getInt("x_coordinate"),
                    rs.getInt("y_coordinate"),
                    rs.getBoolean("is_active"));
        } catch (IllegalArgumentException e) {
            // The database contains a value our enum does not know about.
            throw new SQLException("Unknown location_type in database: " + e.getMessage(), e);
        }
    }
}
