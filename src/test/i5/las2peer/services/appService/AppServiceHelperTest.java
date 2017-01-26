package i5.las2peer.services.appService;

import org.junit.Test;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

import static org.junit.Assert.*;

/**
 * Created by adabru on 23.01.17.
 */
public class AppServiceHelperTest {
    private AppService.User u1 = new AppService.User("#a", "a");
    private AppService.User u2 = new AppService.User("#b", "b");
    private AppServiceHelper getMock(int testNumber) {
        return new AppServiceHelper(
            new DatabaseManager(
                "sa"
                , ""
                , "jdbc:h2:mem:appservicehelpertest_"+testNumber+";DB_CLOSE_DELAY=-1"
                , "testSchema"
                , "./etc/db_migration"
                , "./database"
            ), "Windows;Linux;OS X;Service".split(";")
        );
    }


    @Test
    public void app() {
        AppServiceHelper ash = getMock(0);
        Response r;
        r = ash.addApp("Yeah", new HashMap<>(), u1);
        assertEquals(201, r.getStatus());
        assertEquals(ash.toJson("{\"app\":1}")
            , ash.toJson((String) r.getEntity()));
        r = ash.addApp("banana", new HashMap<>(), u1);
        assertEquals(201, r.getStatus());
        assertEquals(ash.toJson("{\"app\":2}")
            , ash.toJson((String) r.getEntity()));
        r = ash.getApp(1);
        assertEquals(200, r.getStatus());
        assertEquals( ash.toJson("{\"creator\":\"a\",\"description\":\"Yeah\",\"config\":{},\"rating\":0.0}")
            , ash.toJson((String) r.getEntity()));
        r = ash.editApp(1, "jo", new HashMap<>(), u1);
        assertEquals(200, r.getStatus());
        r = ash.getApp(1);
        assertEquals( ash.toJson("{\"creator\":\"a\",\"description\":\"jo\",\"config\":{},\"rating\":0.0}")
            , ash.toJson((String) r.getEntity()));
        r = ash.editApp(1, "cake", new HashMap<>(), u2);
        assertEquals(403, r.getStatus());
        r = ash.addMaintainer(1, u2.oidc_id, u1);
        assertEquals(200, r.getStatus());
        r = ash.editApp(1, "cake", new HashMap<>(), u2);
        assertEquals(200, r.getStatus());
        r = ash.deleteApp(1, u1);
        assertEquals(200, r.getStatus());
        r = ash.getApp(1);
        assertEquals(404, r.getStatus());
    }

    @Test
    public void comment() {
        AppServiceHelper ash = getMock(1);
        Response r;
        r = ash.addApp("Yeah", new HashMap<>(), u1);
        assertEquals(201, r.getStatus());
        int time1 = (int) (new Date().getTime() / 1000);
        r = ash.addComment(1,"apple", u1);
        int time2 = (int) (new Date().getTime() / 1000);
        assertEquals(200, r.getStatus());
        r = ash.addComment(1,"banana", u2);
        assertEquals(200, r.getStatus());
        r = ash.getComments(1);
        assertEquals(200, r.getStatus());
        JsonArray ja = (JsonArray) ash.toJson((String) r.getEntity());
        assertEquals(2, ja.size());
        assertEquals("a", ja.getJsonObject(0).getString("creator"));
        int timestamp = ja.getJsonObject(0).getInt("timestamp");
        assertTrue("timestamp "+timestamp+" ∉ ["+time1+", "+time2+"]"
            , time1 <= timestamp && timestamp <= time2);
        assertEquals("apple", ja.getJsonObject(0).getString("text"));
        assertEquals("b", ja.getJsonObject(1).getString("creator"));
        timestamp = ja.getJsonObject(1).getInt("timestamp");
        assertTrue("timestamp "+timestamp+" < "+time2
            , time2 <= timestamp);
        assertEquals("banana", ja.getJsonObject(1).getString("text"));
        r = ash.deleteComment(1, ja.getJsonObject(0).getInt("timestamp"), u1);
        assertEquals(200, r.getStatus());
        r = ash.getComments(1);
        assertEquals(200, r.getStatus());
        assertEquals(1, ((JsonArray) (ash.toJson((String) r.getEntity()))).size());
    }

    @Test
    public void media() throws IOException {
        AppServiceHelper ash = getMock(2);
        Response r;
        r = ash.addApp("Yeah", new HashMap<>(), u1);
        assertEquals(201, r.getStatus());
        r = ash.addMedia(1, "kiwi", "application/json", new ByteArrayInputStream(new byte[]{4,5,6}), u1);
        assertEquals(200, r.getStatus());
        r = ash.getMedia(1, "kiwi");
        assertEquals(200, r.getStatus());
        assertEquals("application/json", r.getMediaType().toString());
        InputStream medium = (InputStream) r.getEntity();
        assertEquals(4, medium.read());
        assertEquals(5, medium.read());
        assertEquals(6, medium.read());
    }

    @Test
    public void rating() {
        AppServiceHelper ash = getMock(3);
        Response r;
        r = ash.addApp("Yeah", new HashMap<>(), u1);
        assertEquals(201, r.getStatus());
        r = ash.rateApp(1, 6, u1);
        assertEquals(400, r.getStatus());
        r = ash.rateApp(1, 0, u1);
        assertEquals(400, r.getStatus());
        r = ash.rateApp(1, 1, u1);
        assertEquals(200, r.getStatus());
        r = ash.rateApp(1, 4, u2);
        assertEquals(200, r.getStatus());
        r = ash.rateApp(1, 1, u1);
        assertEquals(200, r.getStatus());
        r = ash.getApp(1);
        assertEquals(200, r.getStatus());
        JsonObject ja = (JsonObject) ash.toJson((String) r.getEntity());
        assertEquals(2.5, ja.getJsonNumber("rating").doubleValue(), .1);
    }

    @Test
    public void search() {
        AppServiceHelper ash = getMock(4);
        Response r;
        /*1*/ ash.addApp("ab cats cd", new HashMap<>(), u1);
        /*2*/ ash.addApp("(ab <ca-(t)s> cd}", new HashMap<>(), u1);
        /*3*/ ash.addApp("ab CaTs cd", new HashMap<>(), u1);
        /*4*/ ash.addApp("ab acts cd", new HashMap<>(), u1);
        /*5*/ ash.addApp("ab catt cd", new HashMap<>(), u1);
        /*6*/ ash.addApp("ab dat cd", new HashMap<>(), u1);
        /*7*/ ash.addApp("ab act cd", new HashMap<>(), u1);
        /*8*/ ash.addApp("ab cttt cd", new HashMap<>(), u1);
        r = ash.searchApp("cat");
        assertEquals(200, r.getStatus());
        JsonArray ja = (JsonArray) ash.toJson((String) r.getEntity());
        assertEquals(ja.toString(), 4, ja.size());
        assertEquals(ja.toString(), 1, ja.getJsonObject(0).getInt("app"));
        assertEquals(ja.toString(), 2, ja.getJsonObject(1).getInt("app"));
        assertEquals(ja.toString(), 3, ja.getJsonObject(2).getInt("app"));
        assertEquals(ja.toString(), 5, ja.getJsonObject(3).getInt("app"));
        r = ash.searchApp("cats");
        assertEquals(200, r.getStatus());
        ja = (JsonArray) ash.toJson((String) r.getEntity());
        assertEquals(ja.toString(), 5, ja.size());
        assertEquals(ja.toString(), 1, ja.getJsonObject(0).getInt("app"));
        assertEquals(ja.toString(), 2, ja.getJsonObject(1).getInt("app"));
        assertEquals(ja.toString(), 3, ja.getJsonObject(2).getInt("app"));
        assertEquals(ja.toString(), 4, ja.getJsonObject(3).getInt("app"));
        assertEquals(ja.toString(), 5, ja.getJsonObject(4).getInt("app"));
    }

    @Test
    public void platform() {
        AppServiceHelper ash = getMock(5);
        Response r;
        r = ash.getPlatforms();
        assertEquals(200, r.getStatus());
        assertEquals(ash.toJson("[\"Windows\",\"Linux\",\"OS X\",\"Service\"]")
            , ash.toJson((String) r.getEntity()));

        Map<String, Object> config = new HashMap<>();
            Map<String, Object> export = new HashMap<>();
            config.put("export", export);
            export.put("Windows", 0);
            export.put("Linux", 0);
        ash.addApp("hello", config, u1);
            export.put("OS X", 0);
        ash.addApp("hello", config, u1);
            export.put("banana", 0);
        ash.addApp("hello", config, u1);
        r = ash.getAppsByPlatform("Windows");
        assertEquals(200, r.getStatus());
        JsonArray ja = (JsonArray) ash.toJson((String) r.getEntity());
        assertEquals(ja.toString(), 3, ja.size());
        r = ash.getAppsByPlatform("banana");
        assertEquals(200, r.getStatus());
        ja = (JsonArray) ash.toJson((String) r.getEntity());
        assertEquals(ja.toString(), 1, ja.size());
    }
}
