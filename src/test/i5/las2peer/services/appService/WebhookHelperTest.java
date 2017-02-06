package i5.las2peer.services.appService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.Test;

import javax.json.JsonStructure;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.*;

import static org.junit.Assert.*;

/**
 * Created by adabru on 06.02.17.
 */
public class WebhookHelperTest {
    private static class ServiceRunnerVerifier {
        HttpServer server;
        BlockingQueue<String> got = new LinkedBlockingQueue<>();
        BlockingQueue<Response> send = new LinkedBlockingQueue<>();
        int port;

        public ServiceRunnerVerifier(int port) throws IOException {
            this.port = port;
            server = HttpServer.create(new InetSocketAddress(port), 0);
            server.createContext("/", httpExchange -> {
                try {
                    // receive request
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[2048]; int n;
                    InputStream is = httpExchange.getRequestBody();
                    while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                    is.close();
                    String req = baos.toString("UTF-8");
                    got.add(req);

                    // send response
                    byte[] content;
                    Response resp = send.poll();
                    if(resp == null) throw new IllegalStateException("" +
                        "There is no response prepared for the service-runner verifier!\n" +
                        "Must be a response to:\n" +
                        httpExchange.getRequestMethod()  + " " + httpExchange.getRequestURI() + "\n" +
                        req);
                    if (resp.getEntity() != null) content = ((String) resp.getEntity()).getBytes();
                    else                          content = new byte[0];
                    httpExchange.sendResponseHeaders(resp.getStatus(), content.length);
                    OutputStream os = httpExchange.getResponseBody();
                    os.write(content);
                    os.close();
                } catch(Exception e) { e.printStackTrace(); }
            });
            server.setExecutor(null); // creates a default executor
            server.start();
        }
        public void prepare(Response resp) { send.add(resp); }
        public String await(int timeout) throws InterruptedException, TimeoutException {
            String response = got.poll(timeout, TimeUnit.MILLISECONDS);
            if (response == null) throw new TimeoutException();
            return response;
        }
        public String url() { return "http://localhost:"+port+"/"; }
    }
    private JsonStructure json(Object s) { return JsonHelper.parse(((String)s).replaceAll("'","\"")); }
    private Map<String,Object> map(String s) { return (Map) JsonHelper.toCollection(json(s)); }

    @Test
    public void buildHook() throws Exception {
        ServiceRunnerVerifier srv = new ServiceRunnerVerifier(7002);
        DatabaseManager dm = new DatabaseManager("sa", ""
                , "jdbc:h2:mem:webhookhelpertest_1;DB_CLOSE_DELAY=-1", "testSchema"
                , "./etc/db_migration", "./database");
        WebhookHelper wh = new WebhookHelper(dm, srv.url());
        AppServiceHelper ash = new AppServiceHelper(dm, "Windows;Linux;OS X;Service".split(";"));
        AppService.User u = new AppService.User("anonymous", "anonymous");
        Response r;

        // test build triggers
        // app:
        // - sync with repo2
        // - new versions when commiting on repo1
        // - new build for 'latest' when commiting on repo1 or repo2
        ash.addApp(map("{'autobuild':[" +
                "{" +
                    "'trigger': 'commit'," +
                    "'url': 'http://repo1'," +
                    "'change': 'commit'," +
                    "'prefixes': ['v']" +
                "},{" +
                    "'trigger': 'release'," +
                    "'url': 'http://repo2'," +
                    "'change': 'sync'" +
                "},{" +
                    "'trigger': 'commit'," +
                    "'url': 'http://repo1'," +
                    "'change': 'none'," +
                    "'prefixes': ['latest']" +
                "},{" +
                    "'trigger': 'commit'," +
                    "'url': 'http://repo2'," +
                    "'change': 'none'," +
                    "'prefixes': ['latest']" +
                "}" +
            "]," +
            "'versions':{" +
                "'latest':{}" +
            "}" +
        "}"), u);

        // release in repo2
        srv.prepare(Response.ok("apple").build());
        r = wh.webhook(json("{" +
            "'ref':'refs/tags/v0.0.1'," +
            "'head_commit': {" +
                "'id':'111'" +
            "}," +
            "'repository':{" +
                "'url':'http://repo2'" +
            "}}").toString()
            , null);
        assertEquals(json("{'app':1,'version':'v0.0.1','env':{'http://repo2':{'tag':'v0.0.1','sha':'111'}}}"), json(srv.await(1000)));
        assertEquals(200, r.getStatus());
        assertEquals(json("{'1,v0.0.1':{'body':'apple','status':200}}"), json(r.getEntity()));

        // commit in repo1
        srv.prepare(Response.ok("apple").build());
        srv.prepare(Response.ok("banana").build());
        r = wh.webhook(json("{" +
                "'ref':'refs/heads/master'," +
                "'head_commit': {" +
                    "'id':'222'" +
                "}," +
                "'repository':{" +
                    "'url':'http://repo1'" +
            "}}").toString()
            , null);
        assertEquals(json("{'app':1,'version':'v0.0.2-0','env':{'http://repo1':{'sha':'222'},'http://repo2':{'tag':'v0.0.1','sha':'111'}}}")
            , json(srv.await(1000)));
        assertEquals(json("{'app':1,'version':'latest'}"), json(srv.await(1000)));
        assertEquals(200, r.getStatus());
        assertEquals(json("{" +
            "'1,latest':{'body':'banana','status':200}," +
            "'1,v0.0.2-0':{'body':'apple','status':200}" +
        "}"), json(r.getEntity()));

        // commit in repo2
        srv.prepare(Response.ok("apple").build());
        r = wh.webhook(json("{" +
                "'ref':'refs/heads/master'," +
                "'head_commit': {" +
                    "'id':'333'" +
                "}," +
                "'repository':{" +
                    "'url':'http://repo2'" +
            "}}").toString()
            , null);
        assertEquals(json("{'app':1,'version':'latest'}"), json(srv.await(1000)));
        assertEquals(200, r.getStatus());
        assertEquals(json("{" +
            "'1,latest':{'body':'apple','status':200}" +
        "}"), json(r.getEntity()));


        // test configuration merging
        ash.addApp(map("{'autobuild':[" +
                "{" +
                    "'trigger': 'commit'," +
                    "'url': 'http://repo3'," +
                    "'change': 'commit'," +
                    "'prefixes': ['v']" +
                "}" +
            "]," +
            "'versions':{" +
                "'v0':{'env':{'a':0,'b':0,'c':0,'d':0,'e':0}}," +
                "'v0.1':{'env':{'a':1,'b':1,'c':1,'d':1}}," +
                "'v0.1.1-0':{'env':{'a':2,'b':2,'c':2}}," +
                "'v0.1.1-5':{'env':{'a':3,'b':3}}," +
                "'v3':{'env':{'a':null}}" +
            "}" +
        "}"), u);

        // commit in repo3
        srv.prepare(Response.ok("apple").build());
        r = wh.webhook(json("{" +
            "'ref':'refs/heads/master'," +
            "'head_commit': {" +
                "'id':'444'" +
            "}," +
            "'repository':{" +
                "'url':'http://repo3'" +
            "}}").toString()
            , null);
        assertEquals(json("{'app':2,'version':'v4-0','env':{'b':3,'c':2,'d':1,'e':0,'http://repo3':{'sha':'444'}}}"), json(srv.await(1000)));
        assertEquals(200, r.getStatus());
        assertEquals(json("{" +
                "'2,v4-0':{'body':'apple','status':200}" +
                "}"), json(r.getEntity()));
    }

    @Test
    public void deployHook() throws Exception {
        ServiceRunnerVerifier srv = new ServiceRunnerVerifier(7003);
        DatabaseManager dm = new DatabaseManager("sa", ""
                , "jdbc:h2:mem:webhookhelpertest_2;DB_CLOSE_DELAY=-1", "testSchema"
                , "./etc/db_migration", "./database");
        WebhookHelper wh = new WebhookHelper(dm, srv.url());
        AppServiceHelper ash = new AppServiceHelper(dm, "Windows;Linux;OS X;Service".split(";"));
        AppService.User u = new AppService.User("anonymous", "anonymous");
        Response r;

        ash.addApp(map("{}"), u);

        r = wh.webhook(json("{'app':1,'version': 'v1.2-1','buildid':'1486300000000'}").toString(), null);
        assertEquals(200, r.getStatus());

        srv.prepare(Response.ok(json("{'iid':654,'app':1,'version':'v1.2-1','buildid':1486300000000}").toString()).build());
        r = wh.registerHook(654, "build;commit;major");
        assertEquals(200, r.getStatus());
        assertEquals("", srv.await(1000));

        // build ✔
        srv.prepare(Response.ok(json("{'iid':654,'app':1,'version':'v1.2-1','buildid':1486300000000}").toString()).build());
        srv.prepare(Response.ok("apple").build());
        r = wh.webhook(json("{'app':1,'version': 'v1.2-1','buildid':1486300000001}").toString(), null);
        assertEquals(200, r.getStatus());
        assertEquals("", srv.await(1000));
        assertEquals(json("{'app':1,'version':'v1.2-1'}").toString(), srv.await(1000));
        assertEquals(json("{'654':{'body':'apple','status':200}}"), json(r.getEntity()));
        // commit ✔
        srv.prepare(Response.ok(json("{'iid':654,'app':1,'version':'v1.2-1','buildid':1486300000001}").toString()).build());
        srv.prepare(Response.ok("apple").build());
        r = wh.webhook(json("{'app':1,'version': 'v1.2-2','buildid':1486300000001}").toString(), null);
        assertEquals(200, r.getStatus());
        assertEquals("", srv.await(1000));
        assertEquals(json("{'app':1,'version':'v1.2-2'}").toString(), srv.await(1000));
        assertEquals(json("{'654':{'body':'apple','status':200}}"), json(r.getEntity()));
        // patch ✘
        srv.prepare(Response.ok(json("{'iid':654,'app':1,'version':'v1.2-2','buildid':1486300000001}").toString()).build());
        r = wh.webhook(json("{'app':1,'version': 'v1.2.0-2','buildid':1486300000001}").toString(), null);
        assertEquals(200, r.getStatus());
        assertEquals("", srv.await(1000));
        assertEquals(json("{}"), json(r.getEntity()));
        // minor ✘
        srv.prepare(Response.ok(json("{'iid':654,'app':1,'version':'v1.2-2','buildid':1486300000001}").toString()).build());
        r = wh.webhook(json("{'app':1,'version': 'v1.3-2','buildid':1486300000001}").toString(), null);
        assertEquals(200, r.getStatus());
        assertEquals("", srv.await(1000));
        assertEquals(json("{}"), json(r.getEntity()));
        // major ✔
        srv.prepare(Response.ok(json("{'iid':654,'app':1,'version':'v1.2-2','buildid':1486300000001}").toString()).build());
        srv.prepare(Response.ok("apple").build());
        r = wh.webhook(json("{'app':1,'version': 'v2.2-2','buildid':1486300000001}").toString(), null);
        assertEquals(200, r.getStatus());
        assertEquals("", srv.await(1000));
        assertEquals(json("{'app':1,'version':'v2.2-2'}").toString(), srv.await(1000));
        assertEquals(json("{'654':{'body':'apple','status':200}}"), json(r.getEntity()));
    }
}
