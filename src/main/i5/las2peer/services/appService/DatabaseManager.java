package i5.las2peer.services.appService;

import org.flywaydb.core.Flyway;

import java.io.InputStream;
import java.sql.*;

/**
 * Created by adabru on 28.11.16.
 */
public class DatabaseManager {
    private Connection con;
    private Flyway flyway;

    private String jdbcLogin;
    private String jdbcPass;
    private String jdbcUrl;
    private String jdbcSchema;

    private String migrationPath;
    private String backupPath;

    public DatabaseManager(String jdbcLogin, String jdbcPass, String jdbcUrl,
                           String jdbcSchema, String migrationPath, String backupPath) {
        this.jdbcLogin = jdbcLogin;
        this.jdbcPass = jdbcPass;
        this.jdbcUrl = jdbcUrl;
        this.jdbcSchema = jdbcSchema;
        if (!jdbcUrl.startsWith("jdbc:h2:"))
            throw new IllegalArgumentException("Only compatible with h2 databases, invalid url: "+jdbcUrl);
        this.migrationPath = migrationPath;
        this.backupPath = backupPath;
    }

    private Connection getConnection() throws SQLException {
        if (con != null)
            return con;

        // database migration
        if (flyway == null) {
            flyway = new Flyway();
            flyway.setDataSource(jdbcUrl, jdbcLogin, jdbcPass);
            flyway.setSchemas(jdbcSchema);
            flyway.setLocations("filesystem:"+migrationPath);
            flyway.migrate();
        }

        con = DriverManager.getConnection(jdbcUrl, jdbcLogin, jdbcPass);

        Statement stmt = con.createStatement( );
        // h2 specific
        stmt.execute("SET SCHEMA \""+jdbcSchema+"\"");

        return con;
    }

    public ResultSet query(String sql, Object... arguments) throws SQLException {
        PreparedStatement pstmt = getConnection().prepareStatement(sql);
        for (int i=0 ; i<arguments.length ; i++)
            pstmt.setObject(i+1, arguments[i]);
        return pstmt.executeQuery();
    }
    public static class UpdateResult {
        public int rows;
        public ResultSet generated;
        public UpdateResult(int rows, ResultSet generated) {
            this.rows = rows;
            this.generated = generated;
        }
    }
    public UpdateResult update(String sql, Object... arguments) throws SQLException {
        PreparedStatement pstmt = getConnection().prepareStatement(sql);
        for (int i=0 ; i<arguments.length ; i++)
            if (arguments[i] instanceof InputStream)
                pstmt.setBinaryStream(i+1, (InputStream) arguments[i]);
            else
                pstmt.setObject(i+1, arguments[i]);
        return new UpdateResult(pstmt.executeUpdate(), pstmt.getGeneratedKeys());
    }

    public boolean backup() throws SQLException {
        Statement stmt = getConnection().createStatement( );
        // h2 specific
        return stmt.execute( "SCRIPT DROP TO '"+backupPath+"/backup.sql'" );
    }

    public boolean restore() throws SQLException {
        Statement stmt = getConnection().createStatement( );
        // h2 specific
        return stmt.execute( "RUNSCRIPT FROM '"+backupPath+"/backup.sql'" );
    }

    /*
     * function for testing
     */
    public void resetTables() throws SQLException {
        if (con != null) {
            con.close();
            con = null;
        }
        if (flyway != null) {
            flyway.clean();
            flyway = null;
        } else {
            con = DriverManager.getConnection(jdbcUrl, jdbcLogin, jdbcPass);
            con.createStatement().execute("DROP SCHEMA IF EXISTS \"" + jdbcSchema + "\"");
            con.close();
            con = null;
        }
    }
}
