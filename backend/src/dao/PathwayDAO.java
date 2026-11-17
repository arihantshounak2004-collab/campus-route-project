package dao;

import database.DatabaseConnection;
import model.CrowdLevel;
import model.Pathway;
import model.PathwayStatus;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * PathwayDAO (Data Access Object for the "pathways" table).
 * Phase 6/10 will add methods to update crowd level and status.
 */
public class PathwayDAO {

    private static final String SELECT_COLUMNS =
            "SELECT pathway_id, source_location_id, destination_location_id, distance, "
          + "estimated_time, crowd_level, pathway_status, is_accessible FROM pathways ";

    /** Returns ALL pathways (open, restricted and blocked), ordered by id. */
    public List<Pathway> findAll() throws SQLException {
        List<Pathway> result = new ArrayList<>();
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(SELECT_COLUMNS + "ORDER BY pathway_id");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRow(rs));
            }
        }
        return result;
    }

    /** Returns the pathway with this id, or null if there is none. */
    public Pathway findById(int id) throws SQLException {
        try (Connection con = DatabaseConnection.getConnection();
             PreparedStatement ps = con.prepareStatement(SELECT_COLUMNS + "WHERE pathway_id = ?")) {
            ps.setInt(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? mapRow(rs) : null;
            }
        }
    }

    private Pathway mapRow(ResultSet rs) throws SQLException {
        try {
            return new Pathway(
                    rs.getInt("pathway_id"),
                    rs.getInt("source_location_id"),
                    rs.getInt("destination_location_id"),
                    rs.getDouble("distance"),
                    rs.getInt("estimated_time"),
                    CrowdLevel.parse(rs.getString("crowd_level")),
                    PathwayStatus.parse(rs.getString("pathway_status")),
                    rs.getBoolean("is_accessible"));
        } catch (IllegalArgumentException e) {
            throw new SQLException("Unknown crowd_level or pathway_status in database: " + e.getMessage(), e);
        }
    }
}
