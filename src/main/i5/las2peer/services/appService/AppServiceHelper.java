package i5.las2peer.services.appService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.*;
import javax.json.stream.JsonGenerator;
import javax.ws.rs.core.Response;
import java.io.*;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by adabru on 23.01.17.
 */
public class AppServiceHelper {
    private static Logger l = LoggerFactory.getLogger(AppServiceHelper.class.getName());

    private DatabaseManager dm;
    private String[] platforms;

    public AppServiceHelper(DatabaseManager dm, String[] platforms) {
        this.platforms = platforms;
        this.dm = dm;
        // TODO dm.restore();
    }

    private void touchUser(AppService.User user) throws SQLException {
        ResultSet rs = dm.query("SELECT * FROM users WHERE oidc_id=?", user.oidc_id);
        if (rs.next()) {
            if (user.username != null && !rs.getString("username").equals(user.username))
                dm.update("UPDATE users SET username=? WHERE oidc_id=?", user.username, user.oidc_id);
        } else {
            dm.update("INSERT INTO users VALUES (?,?)", user.oidc_id, (user.username == null) ? "" : user.username);
        }
    }

    private String toSearchText(String text) {
        return text.replaceAll("[\t\n ]+"," ").replaceAll("[^a-zA-Z0-9 ]", "").toLowerCase();
    }
    private String extractPlatforms(Map<String, Object> config) {
        StringBuilder sb = new StringBuilder();
        for (String platform : ((Map<String, Object>) config.getOrDefault("export", new HashMap<>())).keySet())
            sb.append(platform+";");
        return sb.toString();
    }
    public Response addApp(String description, Map<String, Object> config, AppService.User user) {
        try {
            touchUser(user);
            ResultSet rs = dm.update("INSERT INTO apps VALUES (?,?,?,?,?,?)"
               , null
               , user.oidc_id
               , description
               , toSearchText(description)
               , extractPlatforms(config)
               , JsonHelper.toString(config)
            ).generated;
            rs.next();
            int app = rs.getInt(1);
            dm.update("INSERT INTO maintainers VALUES (?,?)", app, user.oidc_id);

            return Response.created(URI.create("http://./"+app)).entity("{\"app\":"+app+"}").build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
    public Response editApp(int app, String description, Map<String, Object> config, AppService.User user) {
        try {
            touchUser(user);
            if (!dm.query("SELECT * FROM maintainers WHERE app=? AND maintainer=?", app, user.oidc_id).next())
                return Response.status(403).build();

            dm.update("UPDATE apps SET (description,search_text,platform,config)=(?,?,?,?) WHERE app=?"
                    , description
                    , toSearchText(description)
                    , extractPlatforms(config)
                    , JsonHelper.toString(config)
                    , app
            );
            return Response.ok().build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
    public Response deleteApp(int app, AppService.User user) {
        try {
            touchUser(user);
            ResultSet rs = dm.query("SELECT creator FROM apps WHERE app=?", app);
            if (!rs.next())
                return Response.status(404).build();
            if (!rs.getString("creator").equals(user.oidc_id))
                return Response.status(403).build();
            dm.update("DELETE FROM apps WHERE app=?", app);
            return Response.ok().build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
    public Response getApp(int app) {
        try {
            ResultSet rs = dm.query("SELECT description,config,username FROM apps JOIN users ON creator=oidc_id WHERE app=?", app);
            if (!rs.next())
                return Response.status(404).build();
            ResultSet rs2 = dm.query("SELECT AVG(CAST(value AS DOUBLE)) FROM ratings WHERE app=?", app);
            rs2.next();
            return Response.ok("{" +
                    "\"creator\":\"" + rs.getString("username") + "\"" +
                    ",\"description\":\"" + rs.getString("description") + "\"" +
                    ",\"config\":" + rs.getString("config") +
                    ",\"rating\":" + rs2.getDouble(1) +
                "}").build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }

    public Response addMaintainer(int app, String oidc_id, AppService.User user) {
        try {
            touchUser(user);
            if (!dm.query("SELECT * FROM maintainers WHERE app=? AND maintainer=?", app, user.oidc_id).next())
                return Response.status(403).build();
            if (dm.query("SELECT * FROM maintainers WHERE app=? AND maintainer=?", app, oidc_id).next())
                return Response.ok().build();
            dm.update("INSERT INTO maintainers VALUES (?,?)", app, oidc_id);
            return Response.ok().build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }

    public Response searchApp(String query) {
        StringBuilder sqlQuery = new StringBuilder("SELECT app,description FROM apps WHERE TRUE");
        for (String k : query.split(" ")) {
            sqlQuery.append(" AND ");
            if (k.length() < 4)
                sqlQuery.append("search_text REGEXP '"+k+"'");
            else {
                sqlQuery.append("search_text REGEXP '.^"); // .^ never matches
                k = k.replaceAll("[^a-zA-Z0-9]", " ").toLowerCase();
                // edit distance 1: /cat/ becomes /(c?.?at)|(ca?.?t)|(cat?.?)/
                for (int i=1; i <= k.length(); i++)
                    sqlQuery.append("|("+k.substring(0,i)+"?.?"+k.substring(i)+")");
                // letter swaps: /cat/ becomes /(.ct)|(c.a)/
                for (int i=1; i < k.length(); i++)
                    sqlQuery.append("|("+k.substring(0,i-1)+"."+k.charAt(i-1)+k.substring(i+1)+")");
                sqlQuery.append("'");
            }
        }
        try {
            ResultSet rs = dm.query(sqlQuery.toString());
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JsonGenerator jg = Json.createGenerator(baos);
            jg.writeStartArray();
            while (rs.next()) {
                jg.writeStartObject()
                    .write("app", rs.getInt("app"))
                    .write("description", rs.getString("description"))
                .writeEnd();
            }
            jg.writeEnd().close();
            return Response.ok(baos.toString("utf8")).build();
        } catch (SQLException | UnsupportedEncodingException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }

    public Response getPlatforms() {
        return Response.ok("[\""+String.join("\",\"",platforms)+"\"]").build();
    }

    public Response getAppsByPlatform(String platform) {
        try {
            ResultSet rs;
            if (platform.equals("all"))
                rs = dm.query("SELECT app FROM apps");
            else
                rs = dm.query("SELECT app FROM apps WHERE platform REGEXP ?", platform);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JsonGenerator jg = Json.createGenerator(baos);
            jg.writeStartArray();
            while (rs.next())
                jg.write(rs.getString("app"));
            jg.writeEnd().close();
            return Response.ok(baos.toString("utf8")).build();
        } catch (SQLException | UnsupportedEncodingException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }

    public Response addComment(int app, String text, AppService.User user) {
        try {
            touchUser(user);
            int now = (int) (new Date().getTime() / 1000);
            dm.update("INSERT INTO comments VALUES (?,?,?,?)", app, user.oidc_id, now, text);
            return Response.ok().build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
    public Response deleteComment(int app, int timestamp, AppService.User user) {
        try {
            touchUser(user);
            if (0 == dm.update("DELETE FROM comments WHERE (app,creator,timestamp)=(?,?,?)", app, user.oidc_id, timestamp).rows)
                return Response.status(404).build();
            return Response.ok().build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
    public Response getComments(int app) {
        try {
            ResultSet rs = dm.query("SELECT username,timestamp,text FROM comments JOIN users ON creator=oidc_id WHERE app=? ORDER BY timestamp", app);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JsonGenerator jg = Json.createGenerator(baos);
            jg.writeStartArray();
            while (rs.next())
                jg.writeStartObject()
                    .write("creator",rs.getString("username"))
                    .write("timestamp",rs.getInt("timestamp"))
                    .write("text",rs.getString("text"))
                .writeEnd();
            jg.writeEnd().close();
            return Response.ok(baos.toString("utf8")).build();
        } catch (SQLException | UnsupportedEncodingException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }

    public Response rateApp(int app, int value, AppService.User user) {
        try {
            touchUser(user);
            if (value < 1 || value > 5)
                return Response.status(400).build();
            if (dm.query("SELECT * FROM ratings WHERE (app,creator)=(?,?)", app, user.oidc_id).next())
                dm.update("UPDATE ratings SET value=? WHERE (app,creator)=(?,?)", value, app, user.oidc_id);
            else
                dm.update("INSERT INTO ratings VALUES (?,?,?)", app, user.oidc_id, value);
            return Response.ok().build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }

    public Response addMedia(int app, String name, String type, InputStream blob, AppService.User user) {
        try {
            touchUser(user);
            if (!dm.query("SELECT * FROM maintainers WHERE app=? AND maintainer=?", app, user.oidc_id).next())
                return Response.status(403).build();
            dm.update("INSERT INTO media VALUES (?,?,?,?)", app, name, type, blob);
            return Response.ok().build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
    public Response getMedia(int app, String name) {
        try {
            ResultSet rs = dm.query("SELECT * FROM media WHERE (app,name)=(?,?)", app, name);
            if (!rs.next())
                return Response.status(404).build();
            return Response.ok().type(rs.getString("type")).entity(rs.getBinaryStream("blob")).build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
}
