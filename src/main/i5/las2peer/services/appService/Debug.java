package i5.las2peer.services.appService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Created by adabru on 26.01.17.
 */
public class Debug {
    private static Logger l = LoggerFactory.getLogger(Debug.class.getName());

    public static void printTable(DatabaseManager dm, String table) {
        try {
            ResultSet rs = null;
            rs = dm.query("SELECT * FROM `"+table+"`");
            StringBuilder sb = new StringBuilder("SELECT * FROM `"+table+"`\n");
            while (rs.next()) {
                boolean more = true;
                int i = 1;
                while (more) {
                    try {
                        sb.append(rs.getString(i++) + " ");
                    } catch (SQLException e) {
                        more = false;
                    }
                }
                sb.append("\n");
            }
            l.info(sb.toString());
        } catch (SQLException e) {
            l.error(e.toString());
        }
    }
}
