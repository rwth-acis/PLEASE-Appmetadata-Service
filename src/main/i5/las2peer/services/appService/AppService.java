package i5.las2peer.services.appService;

import i5.las2peer.api.Context;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.restMapper.RESTService;
import i5.las2peer.restMapper.annotations.ServicePath;
import i5.las2peer.security.UserAgent;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.io.IOException;

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

		// TODO
		// GET /search?q=blabla
		// GET /platform
		// GET /platform/{all,p}?page=2
		// GET /apps/id
		// PUT /apps/id
		// POST /apps
		// GET /apps/id/comments
		// POST /apps/id/comments
		// GET /apps/id/media/id
		// POST /apps/id/media
		// POST /apps/id/rating
	}
}
