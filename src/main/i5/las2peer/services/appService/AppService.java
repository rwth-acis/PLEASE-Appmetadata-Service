package i5.las2peer.services.appService;

import i5.las2peer.api.Context;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.p2p.LocalNode;
import i5.las2peer.restMapper.RESTService;
import i5.las2peer.restMapper.annotations.ServicePath;
import i5.las2peer.security.ServiceAgent;
import i5.las2peer.security.UserAgent;

import javax.ws.rs.*;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.InputStream;
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
			return ash.addApp((Map<String, Object>) JsonHelper.toCollection(JsonHelper.parse(content)), getActiveUser());
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
		public Response addMedia(@PathParam("id") int app, @PathParam("name") String name, InputStream content, @javax.ws.rs.core.Context final ContainerRequestContext req) {
			return ash.addMedia(app, name, req.getHeaderString("Content-Type"), content, getActiveUser());
		}

		@POST
		@Path("/apps/{id}/rating")
		public Response addRating(@PathParam("id") int app, int value) {
			return ash.rateApp(app, value, getActiveUser());
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
