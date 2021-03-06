package i5.las2peer.services.appService;

import i5.las2peer.api.Context;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.p2p.LocalNode;
import i5.las2peer.restMapper.RESTService;
import i5.las2peer.restMapper.annotations.ServicePath;
import i5.las2peer.security.ServiceAgent;
import i5.las2peer.security.UserAgent;

import javax.json.JsonArray;
import javax.json.JsonObject;
import javax.json.stream.JsonParsingException;
import javax.ws.rs.*;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import java.io.InputStream;
import java.util.Map;

@ServicePath("apps")
public class AppService extends RESTService {

	AppServiceHelper ash;
	WebhookHelper wh;

	// from properties file, injected by LAS2peer
	public String jdbcLogin;
	public String jdbcPass;
	public String jdbcUrl;
	public String jdbcSchema;
	public String pleasePlatforms;
	public String pleaseServiceRunnerUrl;

	@Override
	protected void initResources() {
		getResourceConfig().register(RootResource.class);
	}

	public AppService() {
		setFieldValues();
		DatabaseManager dm = new DatabaseManager(jdbcLogin, jdbcPass, jdbcUrl, jdbcSchema, "etc/db_migration", "database");
		this.ash = new AppServiceHelper(dm, pleasePlatforms.split(";"));
		this.wh = new WebhookHelper(dm, pleaseServiceRunnerUrl);
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

		private  AppService as;
		private AppServiceHelper ash;
		private WebhookHelper wh;

		public RootResource() {
			this.as = (AppService) Context.getCurrent().getService();
			this.ash = as.ash;
			this.wh = as.wh;
		}

		@GET
		@Produces(MediaType.TEXT_PLAIN)
		public Response signOfLife() {
			return Response.ok(
					"PLEASE service storing app information and hooking git repositories" +
							"\n\n" +
							"\nGET    /search?q=blabla                 : search for app" +
							"\nGET    /platform                        : list filterable platforms" +
							"\nGET    /platform/{p}                    : list apps for platform p" +
							"\nGET    /apps/{id}                       : get app information" +
							"\nPUT    /apps/{id}                       : change app" +
							"\nDELETE /apps/{id}                       : remove app" +
							"\nPOST   /apps/{id}/maintainers           : add maintainer for app" +
							"\nPOST   /apps                            : add new app" +
							"\nGET    /apps/{id}/comments              : get comments for app" +
							"\nPOST   /apps/{id}/comments              : add comment" +
							"\nDELETE /apps/{id}/comments/{timestamp}  : delete comment" +
							"\nGET    /apps/{id}/media/{name}          : get media object" +
							"\nPOST   /apps/{id}/media                 : add media object" +
							"\nPOST   /apps/{id}/rating                : rate app" +
							"\n"
			).build();
		}

		@GET
		@Path("/search")
		@Produces(MediaType.APPLICATION_JSON)
		public Response searchApp(@QueryParam("q") String query) { return ash.searchApp(query); }

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
		public Response editApp(@PathParam("id") int app, String content) {
			return ash.editApp(app, (Map) JsonHelper.toCollection(content), getActiveUser());
		}

		@POST
		@Path("/apps")
		@Produces(MediaType.APPLICATION_JSON)
		public Response addApp(String content) {
			try {
				return ash.addApp((Map<String, Object>) JsonHelper.toCollection(JsonHelper.parse(content)), getActiveUser());
			} catch(JsonParsingException e) {
				return Response.status(400).entity("bad json in post body").build();
			}
		}

		@DELETE
		@Path("/apps/{id}")
		public Response deleteApp(@PathParam("id") int app) {
			return ash.deleteApp(app, getActiveUser());
		}

		@POST
		@Path("/apps/{id}/maintainers")
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
		public Response addComment(@PathParam("id") int app, String text) {
			return ash.addComment(app, text, getActiveUser());
		}

		@DELETE
		@Path("/apps/{id}/comments/{timestamp}")
		public Response deleteComment(@PathParam("id") int app, @PathParam("timestamp") long timestamp) {
			return ash.deleteComment(app, timestamp, getActiveUser());
		}

		@GET
		@Path("/apps/{id}/media/{name}")
		public Response getMedia(@PathParam("id") int app, @PathParam("name") String name) {
			return ash.getMedia(app, name);
		}

		@POST
		@Path("/apps/{id}/media/{name}")
		public Response addMedia(@PathParam("id") int app, @PathParam("name") String name, InputStream content, @javax.ws.rs.core.Context final ContainerRequestContext req) {
			return ash.addMedia(app, name, req.getHeaderString("Content-Type"), content, getActiveUser());
		}

		@POST
		@Path("/apps/{id}/rating")
		public Response addRating(@PathParam("id") int app, int value) {
			return ash.rateApp(app, value, getActiveUser());
		}

		@GET
		@Path("/apps/{id}/rating")
		public Response getRating(@PathParam("id") int app) {
			return ash.getRating(app, getActiveUser());
		}

		@POST
		@Path("/hook")
		public Response webhook(String payload) {
			return wh.webhook(payload, null);
		}

		@PUT
		@Path("/hook/{iid}")
		public Response registerWebhook(@PathParam("iid") int iid, String config) {
			try {
				return wh.registerHook(iid, ((JsonObject) JsonHelper.parse(config)).getString("triggers"));
			} catch(JsonParsingException e) {
				return Response.status(400).entity("bad json in post body").build();
			}
		}

		private User getActiveUser() {
			UserAgent ua = (UserAgent) Context.getCurrent().getMainAgent();
			if(ua.getId() == Context.getCurrent().getLocalNode().getAnonymous().getId())
				return new User("anonymous", "anonymous");
			else
				return new User(String.valueOf(ua.getId()), ua.getLoginName());
		}
	}
}
