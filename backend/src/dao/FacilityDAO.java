package dao;

import database.DatabaseConnection;
import model.AvailabilityStatus;
import model.Facility;
import model.FacilityType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** FacilityDAO - reads the "facilities" table. Phase 10 adds the administrator's add/edit methods. */
public class FacilityDAO {

    private static final String SELECT_COLUMNS =
            "SELECT facility_id, facility_name, facility_type, location_id, description, availability_status FROM facilities ";

    /** Returns all facilities, ordered by id. */
    public List<Facility> findAll() throws SQLException {
        List<Facility> result = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(SELECT_COLUMNS + "ORDER BY facility_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        }
        return result;
    }

    /** Returns the facility with this id, or null. */
    public Facility findById(int id) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(SELECT_COLUMNS + "WHERE facility_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    private Facility mapRow(ResultSet rs) throws SQLException {
        try {
            return new Facility(
                    rs.getInt("facility_id"),
                    rs.getString("facility_name"),
                    FacilityType.parse(rs.getString("facility_type")),
                    rs.getInt("location_id"),
                    rs.getString("description"),
                    AvailabilityStatus.parse(rs.getString("availability_status")));
        } catch (IllegalArgumentException e) {
            throw new SQLException("Unknown facility_type or availability_status in database: " + e.getMessage(), e);
        }
    }
}
