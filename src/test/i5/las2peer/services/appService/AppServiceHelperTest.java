package i5.las2peer.services.appService;

import org.junit.Test;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.JsonStructure;
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
    private JsonStructure json(Object s) { return JsonHelper.parse(((String)s).replaceAll("'","\"")); }
    private Map<String,Object> map(String s) { return (Map) JsonHelper.toCollection(json(s)); }

    @Test
    public void app() {
        AppServiceHelper ash = getMock(0);
        Response r;
        r = ash.addApp(map("{'description':'Yeah'}"), u1);
        assertEquals(201, r.getStatus());
        assertEquals(json("{'app':1}"), json(r.getEntity()));
        r = ash.addApp(map("{'description':'banana'}"), u1);
        assertEquals(201, r.getStatus());
        assertEquals(json("{'app':2}"), json(r.getEntity()));
        r = ash.getApp(1);
        assertEquals(200, r.getStatus());
        assertEquals(json("{'creator':'a','description':'Yeah','autobuild':[],'versions':{},'rating':0.0}")
            , json(r.getEntity()));
        r = ash.editApp(1, map("{'description':'jo'}"), u1);
        assertEquals(200, r.getStatus());
        r = ash.getApp(1);
        assertEquals( json("{'creator':'a','description':'jo','autobuild':[],'versions':{},'rating':0.0}")
            , json(r.getEntity()));
        r = ash.editApp(1, map("{'description':'cake'}"), u2);
        assertEquals(403, r.getStatus());
        r = ash.addMaintainer(1, u2.oidc_id, u1);
        assertEquals(200, r.getStatus());
        r = ash.editApp(1, map("{'description':'cake'}"), u2);
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
        r = ash.addApp(map("{'description':'Yeah'}"), u1);
        assertEquals(201, r.getStatus());
        int time1 = (int) (new Date().getTime() / 1000);
        r = ash.addComment(1,"apple", u1);
        int time2 = (int) (new Date().getTime() / 1000);
        assertEquals(200, r.getStatus());
        r = ash.addComment(1,"banana", u2);
        assertEquals(200, r.getStatus());
        r = ash.getComments(1);
        assertEquals(200, r.getStatus());
        JsonArray ja = (JsonArray) json(r.getEntity());
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
        assertEquals(1, ((JsonArray) (json(r.getEntity()))).size());
    }

    @Test
    public void media() throws IOException {
        AppServiceHelper ash = getMock(2);
        Response r;
        r = ash.addApp(map("{'description':'Yeah'}"), u1);
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
        r = ash.addApp(map("{'description':'Yeah'}"), u1);
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
        JsonObject ja = (JsonObject) json(r.getEntity());
        assertEquals(2.5, ja.getJsonNumber("rating").doubleValue(), .1);
    }

    @Test
    public void search() {
        AppServiceHelper ash = getMock(4);
        Response r;
        /*1*/ ash.addApp(map("{'description':'ab cats cd'}"), u1);
        /*2*/ ash.addApp(map("{'description':'(ab <ca-(t)s> cd}'}"), u1);
        /*3*/ ash.addApp(map("{'description':'ab CaTs cd'}"), u1);
        /*4*/ ash.addApp(map("{'description':'ab acts cd'}"), u1);
        /*5*/ ash.addApp(map("{'description':'ab catt cd'}"), u1);
        /*6*/ ash.addApp(map("{'description':'ab dat cd'}"), u1);
        /*7*/ ash.addApp(map("{'description':'ab act cd'}"), u1);
        /*8*/ ash.addApp(map("{'description':'ab cttt cd'}"), u1);
        r = ash.searchApp("cat");
        assertEquals(200, r.getStatus());
        JsonArray ja = (JsonArray) json(r.getEntity());
        assertEquals(ja.toString(), 4, ja.size());
        assertEquals(ja.toString(), 1, ja.getJsonObject(0).getInt("app"));
        assertEquals(ja.toString(), 2, ja.getJsonObject(1).getInt("app"));
        assertEquals(ja.toString(), 3, ja.getJsonObject(2).getInt("app"));
        assertEquals(ja.toString(), 5, ja.getJsonObject(3).getInt("app"));
        r = ash.searchApp("cats");
        assertEquals(200, r.getStatus());
        ja = (JsonArray) json(r.getEntity());
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
        assertEquals(json("['Windows','Linux','OS X','Service']"), json(r.getEntity()));
        ash.addApp(map("{" +
                "'description':'hello'," +
                "'versions':{'v0':{'export':{'Windows':[], 'Linux':[]}}}" +
            "}"), u1);
        ash.addApp(map("{" +
                "'description':'hello'," +
                "'versions':{'v0':{'export':{'Windows':[], 'Linux':[], 'OS X':[]}}}" +
            "}"), u1);
        ash.addApp(map("{" +
                "'description':'hello'," +
                "'versions':{'v0':{'export':{'Windows':[], 'Linux':[], 'OS X':[], 'banana':[]}}}" +
            "}"), u1);
        r = ash.getAppsByPlatform("Windows");
        assertEquals(200, r.getStatus());
        JsonArray ja = (JsonArray) json(r.getEntity());
        assertEquals(ja.toString(), 3, ja.size());
        r = ash.getAppsByPlatform("banana");
        assertEquals(200, r.getStatus());
        ja = (JsonArray) json(r.getEntity());
        assertEquals(ja.toString(), 1, ja.size());
    }
}
