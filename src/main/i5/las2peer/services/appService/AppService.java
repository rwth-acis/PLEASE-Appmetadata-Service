package i5.las2peer.services.appService;

import i5.las2peer.api.Context;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.restMapper.RESTService;
import i5.las2peer.restMapper.annotations.ServicePath;
import i5.las2peer.security.UserAgent;

import javax.json.*;
import javax.ws.rs.*;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@ServicePath("apps")
public class AppService extends RESTService {

	AppServiceHelper ash;

	// from properties file, injected by LAS2peer
	public String jdbcLogin;
	public String jdbcPass;
	public String jdbcUrl;
	public String jdbcSchema;
	public String pleasePlatforms;

	@Override
	protected void initResources() {
		getResourceConfig().register(RootResource.class);
	}

	public AppService() {
		setFieldValues();
		this.ash = new AppServiceHelper(
			new DatabaseManager(jdbcLogin, jdbcPass, jdbcUrl, jdbcSchema, "etc/db_migration", "database")
			, pleasePlatforms.split(";")
		);
	}

	public static class User {
		public String oidc_id;
		public String username;
		public User(String oidc_id, String username) {
			this.oidc_id = oidc_id; this.username = username;
		}
	}

	@Path("/")
	public static class RootResource {
		// instantiate the logger class
		private final L2pLogger logger = L2pLogger.getInstance(AppService.class.getName());

		private AppServiceHelper ash;

		public RootResource() {
			this.ash = ((AppService) Context.getCurrent().getService()).ash;
		}

		@GET
		@Path("/search")
		@Produces(MediaType.APPLICATION_JSON)
		public Response searchApp(@QueryParam("q") String query) {
			return ash.searchApp(query);
		}

		@GET
		@Path("/platform")
		@Produces(MediaType.APPLICATION_JSON)
		public Response getPlatforms() {
			return ash.getPlatforms();
		}

		@GET
		@Path("/platform/{name}")
		@Produces(MediaType.APPLICATION_JSON)
		public Response getAppsByPlatform(@PathParam("name") String name) {
			return ash.getAppsByPlatform(name);
		}

		@GET
		@Path("/apps/{id}")
		@Produces(MediaType.APPLICATION_JSON)
		public Response getApp(@PathParam("id") int app) {
			return ash.getApp(app);
		}

		@PUT
		@Path("/apps/{id}")
		@Produces(MediaType.APPLICATION_JSON)
		public Response editApp(@PathParam("id") int app, String content) {
			Map<String, Object> contentMap = (Map<String, Object>) toCollection(toJson(content));
			return ash.editApp(app, (String) contentMap.get("description"), (Map<String, Object>) contentMap.get("config"), getActiveUser());
		}

		@POST
		@Path("/apps")
		@Produces(MediaType.APPLICATION_JSON)
		public Response addApp(String content) {
			Map<String, Object> contentMap = (Map<String, Object>) toCollection(toJson(content));
			return ash.addApp((String) contentMap.get("description"), (Map<String, Object>) contentMap.get("config"), getActiveUser());
		}

		@DELETE
		@Path("/apps/{id}")
		@Produces(MediaType.APPLICATION_JSON)
		public Response deleteApp(@PathParam("id") int app) {
			return ash.deleteApp(app, getActiveUser());
		}

		@POST
		@Path("/apps/{id}/maintainers")
		@Produces(MediaType.APPLICATION_JSON)
		public Response addMaintainer(@PathParam("id") int app, String additionalMaintainer) {
			return ash.addMaintainer(app, additionalMaintainer, getActiveUser());
		}

		@GET
		@Path("/apps/{id}/comments")
		@Produces(MediaType.APPLICATION_JSON)
		public Response getComments(@PathParam("id") int app) {
			return ash.getComments(app);
		}

		@POST
		@Path("/apps/{id}/comments")
		@Produces(MediaType.APPLICATION_JSON)
		public Response addComment(@PathParam("id") int app, String text) {
			return ash.addComment(app, text, getActiveUser());
		}

		@DELETE
		@Path("/apps/{id}/comments/{timestamp}")
		@Produces(MediaType.APPLICATION_JSON)
		public Response deleteComment(@PathParam("id") int app, @PathParam("timestamp") int timestamp) {
			return ash.deleteComment(app, timestamp, getActiveUser());
		}

		@GET
		@Path("/apps/{id}/media/{name}")
		public Response getMedia(@PathParam("id") int app, @PathParam("name") String name) {
			return ash.getMedia(app, name);
		}

		@POST
		@Path("/apps/{id}/media/{name}")
		@Produces(MediaType.APPLICATION_JSON)
		public Response addMedia(@PathParam("id") int app, @PathParam("name") String name, InputStream content, @javax.ws.rs.core.Context final ContainerRequestContext req) {
			return ash.addMedia(app, name, req.getHeaderString("content-type"), content, getActiveUser());
		}

		@POST
		@Path("/apps/{id}/rating")
		@Produces(MediaType.APPLICATION_JSON)
		public Response addRating(@PathParam("id") int app, int value) {
			return ash.rateApp(app, value, getActiveUser());
		}

		private User getActiveUser() {
			return new User(String.valueOf(Context.getCurrent().getMainAgent().getId()), null);
		}

		private JsonStructure toJson(String s) {
			JsonReader jr = Json.createReader(new StringReader(s));
			JsonStructure js = jr.read();
			jr.close();

			return js;
		}
		private Object toCollection(JsonValue json) {
			if (json instanceof JsonObject) {
				Map<String, Object> res = new HashMap<>();
				((JsonObject) json).forEach(
						(key, value) ->
								res.put(key, toCollection(value))
				);
				return res;
			} else if (json instanceof JsonArray) {
				List<Object> res = new LinkedList<>();
				((JsonArray) json).forEach(
						(value) ->
								res.add(toCollection(value))
				);
				return res;
			} else if (json instanceof JsonNumber) {
				if (((JsonNumber) json).isIntegral())
					return ((JsonNumber) json).intValue();
				else
					return ((JsonNumber) json).doubleValue();
			} else if (json instanceof JsonString) {
				return ((JsonString) json).getString();
			} else if (json.equals(JsonValue.FALSE)) {
				return false;
			} else if (json.equals(JsonValue.TRUE)) {
				return true;
			} else /*if (json.equals(JsonValue.NULL))*/ {
				return null;
			}
		}
	}
}
