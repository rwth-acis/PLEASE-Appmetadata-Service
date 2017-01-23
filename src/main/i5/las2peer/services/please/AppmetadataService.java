package i5.las2peer.services.please;

import i5.las2peer.api.Context;
import i5.las2peer.logging.L2pLogger;
import i5.las2peer.restMapper.RESTService;
import i5.las2peer.restMapper.annotations.ServicePath;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

@ServicePath("appmetadata")
public class AppmetadataService extends RESTService {

	@Override
	protected void initResources() {
		getResourceConfig().register(RootResource.class);
	}

	public AppmetadataService() {
		// read and set properties values
		// IF THE SERVICE CLASS NAME IS CHANGED, THE PROPERTIES FILE NAME NEED TO BE CHANGED TOO!
		setFieldValues();
	}

	@Path("/")
	public static class RootResource {
		// instantiate the logger class
		private final L2pLogger logger = L2pLogger.getInstance(AppmetadataService.class.getName());

		// get access to the service class
		private final AppmetadataService service = (AppmetadataService) Context.getCurrent().getService();

		@GET
		@Path("/get")
		public Response getTemplate() {
			String returnString = "result";
			return Response.ok().entity(returnString).build();
		}

		@POST
		@Path("/post/{input}")
		@Produces(MediaType.TEXT_PLAIN)
		public Response postTemplate(@PathParam("input") String myInput) {
			String returnString = "";
			returnString += "Input " + myInput;
			return Response.ok().entity(returnString).build();
		}
	}

	// TODO OWN METHODS

}
