package i5.las2peer.services.appService;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import i5.las2peer.p2p.LocalNode;
import i5.las2peer.p2p.ServiceNameVersion;
import i5.las2peer.security.ServiceAgent;
import i5.las2peer.security.UserAgent;
import i5.las2peer.testing.MockAgentFactory;
import i5.las2peer.webConnector.WebConnector;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.sql.SQLException;
import java.util.Base64;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.json.*;
import javax.ws.rs.client.*;
import javax.ws.rs.core.Response;

public class AppServiceTest {

	static LocalNode node;
	static WebConnector connector;
	static ByteArrayOutputStream logStream;

	static WebTarget wt1, wt2;
	static URI base;
	static Response r;
	static String e;

	static Entity app0, app1, app2, app3, app4, app5, comment0, media0;
	static UserAgent user1, user2;

	static ServiceAgent testService;

	private static JsonStructure json(Object s) {
		return JsonHelper.parse(((String)s).replaceAll("'","\""));
	}

	@BeforeClass
	public static void startServer() throws Exception {
		app0 = Entity.entity(json("{'description':'apple'}").toString(), "application/json");
		app1 = Entity.entity(json("{'description':'bacon'}").toString(), "application/json");
		app2 = Entity.entity(json("{'description':'cherry','versions':{'v0':{'export':{'Windows':''}}}}").toString(), "application/json");
		app3 = Entity.entity(json("{'description':'dairy','versions':{'v0':{'export':{'Windows':'','Linux':''}}}}").toString(), "application/json");
		app4 = Entity.entity(json("{'description':'eggs'}").toString(), "application/json");
		app5 = Entity.entity(json("{'description':'fruit'}").toString(), "application/json");
		comment0 = Entity.entity("apple", "text/plain");
		media0 = Entity.entity("apple", "image/png");

		// TODO make connect and read timeout vendor agnostic (i.e. remove jersey dependency) when javax.rs 2.1 gets released (scheduled Q3 2017)
		Client c = ClientBuilder.newClient()
			.property("jersey.config.client.connectTimeout", 5000)
			.property("jersey.config.client.readTimeout", 5000);
		wt1 = c.target("http://127.0.0.1:" + WebConnector.DEFAULT_HTTP_PORT + "/apps/");
		wt1.register((ClientRequestFilter) req -> req.getHeaders().add("Authorization", "Basic "+ Base64.getEncoder().encodeToString((user1.getId()+":adamspass").getBytes("utf8"))));
		wt2 = c.target("http://127.0.0.1:" + WebConnector.DEFAULT_HTTP_PORT + "/apps/");
		wt2.register((ClientRequestFilter) req -> req.getHeaders().add("Authorization", "Basic "+ Base64.getEncoder().encodeToString((user2.getId()+":abelspass").getBytes("utf8"))));

		// start node
		node = LocalNode.newNode();
		user1 = MockAgentFactory.getAdam();
		user1.unlockPrivateKey("adamspass"); // agent must be unlocked in order to be stored
		node.storeAgent(user1);
		user2 = MockAgentFactory.getAbel();
		user2.unlockPrivateKey("abelspass"); // agent must be unlocked in order to be stored
		node.storeAgent(user2);
		node.launch();

		// during testing, the specified service version does not matter
		testService = ServiceAgent.createServiceAgent(
				ServiceNameVersion.fromString(AppService.class.getName() + "@1.0"), "a pass");
		testService.unlockPrivateKey("a pass");

		node.registerReceiver(testService);

		// start connector
		logStream = new ByteArrayOutputStream();

		connector = new WebConnector(true, WebConnector.DEFAULT_HTTP_PORT, false, 1000);
		connector.setLogStream(new PrintStream(logStream));
		connector.start(node);
		Thread.sleep(1000); // wait a second for the connector to become ready
	}

	@AfterClass
	public static void shutDownServer() throws Exception {

		connector.stop();
		node.shutDown();

		connector = null;
		node = null;

		LocalNode.reset();

		System.out.println("Connector-Log:");
		System.out.println("--------------");

		System.out.println(logStream.toString());

	}

	@Before
	public void resetDatabase() throws NoSuchFieldException, IllegalAccessException, SQLException {
		Field dm_field = AppServiceHelper.class.getDeclaredField("dm");
		dm_field.setAccessible(true);
		AppService as = (AppService) testService.getServiceInstance();
		AppServiceHelper ash = as.ash;
		DatabaseManager dm = (DatabaseManager) dm_field.get(ash);
		dm.resetTables();
	}

	@Test
	public void app() {
		r = wt1.path("apps").request().post(app0);
		assertEquals(201, r.getStatus());
		assertEquals(json("{'app':1}")
			, json(r.readEntity(String.class)));
		r = wt1.path("apps/1").request().get();
		assertEquals(200, r.getStatus());
		assertEquals(json("{'creator':'adam','description':'apple','autobuild':[],'versions':{},'rating':0.0,'platforms':[]}")
			, json(r.readEntity(String.class)));
		r = wt1.path("apps/1").request().put(app1);
		assertEquals(200, r.getStatus());
		r = wt1.path("apps/1").request().get();
		assertEquals(200, r.getStatus());
		assertEquals(json("{'creator':'adam','description':'bacon','autobuild':[],'versions':{},'rating':0.0,'platforms':[]}")
			, json(r.readEntity(String.class)));
		r = wt1.path("apps/1").request().delete();
		assertEquals(200, r.getStatus());
		r = wt1.path("apps/1").request().get();
		assertEquals(404, r.getStatus());
	}

	@Test
	public void appPermission() {
		r = wt1.path("apps").request().post(app0);
		r = wt2.path("apps/1").request().put(app1);
		assertEquals(403, r.getStatus());
		r = wt2.path("apps/1").request().delete();
		assertEquals(403, r.getStatus());
		r = wt1.path("apps/1/maintainers").request().post(Entity.entity(user2.getId(), "text/plain"));
		assertEquals(200, r.getStatus());
		r = wt2.path("apps/1").request().put(app1);
		assertEquals(200, r.getStatus());
		r = wt2.path("apps/1").request().delete();
		assertEquals(403, r.getStatus());
		r = wt1.path("apps/1").request().delete();
		assertEquals(200, r.getStatus());
	}

	@Test
	public void appSearch() {
		r = wt1.path("apps").request().post(app2);
		r = wt1.path("apps").request().post(app3);
		r = wt1.path("platform").request().get();
		assertEquals(200, r.getStatus());
		e = r.readEntity(String.class);
		assertTrue("must be json array: "+ e
			, json(e) instanceof JsonArray);
		r = wt1.path("platform/Windows").request().get();
		assertEquals(200, r.getStatus());
		e = r.readEntity(String.class);
		assertTrue("must contain two apps: "+e
			, ((JsonArray) json(e)).size() == 2);
		r = wt1.path("platform/Linux").request().get();
		assertEquals(200, r.getStatus());
		e = r.readEntity(String.class);
		assertTrue("must contain one app: "+e
			, ((JsonArray) json(e)).size() == 1);
		r = wt1.path("search").queryParam("q","ry").request().get();
		assertEquals(200, r.getStatus());
		e = r.readEntity(String.class);
		assertTrue("must contain two apps: "+e
			, ((JsonArray) json(e)).size() == 2);
		r = wt1.path("search").queryParam("q","erry").request().get();
		assertEquals(200, r.getStatus());
		e = r.readEntity(String.class);
		assertTrue("must contain one app: "+e
			, ((JsonArray) json(e)).size() == 1);
		r = wt1.path("apps/1").request().delete();
		r = wt1.path("apps/2").request().delete();
	}

	@Test
	public void comment() {
		r = wt1.path("apps").request().post(app0);
		r = wt1.path("apps/1/comments").request().post(comment0);
		assertEquals(200, r.getStatus());
		r = wt1.path("apps/1/comments").request().get();
		assertEquals(200, r.getStatus());
		e = r.readEntity(String.class);
		assertTrue("must contain comment with text 'apple' : "+e
			,((JsonArray)json(e)).getJsonObject(0).getString("text").equals("apple"));
		long timestamp = ((JsonArray)json(e)).getJsonObject(0).getJsonNumber("timestamp").longValue();
		r = wt1.path("apps/1/comments/"+timestamp).request().delete();
		assertEquals(200, r.getStatus());
		r = wt1.path("apps/1/comments").request().get();
		assertEquals(200, r.getStatus());
		e = r.readEntity(String.class);
		assertTrue("must be empty: "+e
			,((JsonArray)json(e)).size() == 0);
		r = wt1.path("apps/1").request().delete();
	}

	@Test
	public void media() {
		r = wt1.path("apps").request().post(app0);
		r = wt1.path("apps/1/media/media0").request().post(media0);
		assertEquals(200, r.getStatus());
		r = wt1.path("apps/1/media/media0").request().get();
		assertEquals(200, r.getStatus());
		assertEquals("apple", r.readEntity(String.class));
		assertEquals("image/png", r.getHeaderString("Content-Type"));
		r = wt1.path("apps/1").request().delete();
		assertEquals(200, r.getStatus());
		r = wt1.path("apps/1/media/media0").request().get();
		assertEquals(404, r.getStatus());
		r = wt1.path("apps/1").request().delete();
	}

	@Test
	public void rate() {
		r = wt1.path("apps").request().post(app0);
		r = wt1.path("apps/1/rating").request().post(Entity.entity("5","text/plain"));
		assertEquals(200, r.getStatus());
		r = wt2.path("apps/1/rating").request().post(Entity.entity("2","text/plain"));
		assertEquals(200, r.getStatus());
		r = wt2.path("apps/1/rating").request().post(Entity.entity("2","text/plain"));
		assertEquals(200, r.getStatus());
		r = wt1.path("apps/1").request().get();
		assertEquals(200, r.getStatus());
		assertEquals(3.5, ((JsonObject) json(r.readEntity(String.class))).getJsonNumber("rating").doubleValue(), .1);
		r = wt1.path("apps/1").request().delete();
	}

	@Test
	public void hook() {
		r = wt1.path("hook").request().post(Entity.entity(
				json("{'ref':'refs/heads/master','repository':{'url':'http://does.not.exist'}}").toString()
				,"application/json"));
		assertEquals(200, r.getStatus());
	}

	@Test
	public void testLife() throws InterruptedException {
		Response result = wt1.request().get();
		assertEquals(200, result.getStatus());
	}
}
