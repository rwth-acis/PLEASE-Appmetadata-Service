package i5.las2peer.services.appService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.json.JsonObject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by adabru on 02.02.17.
 */
public class WebhookHelper {
    private static Logger l = LoggerFactory.getLogger(WebhookHelper.class.getName());

    DatabaseManager dm;
    WebTarget serviceRunner;

    public WebhookHelper(DatabaseManager dm, String serviceRunnerUrl) {
        this.dm=dm;
        serviceRunner = ClientBuilder.newClient().target(serviceRunnerUrl);
    }

    private static class Version implements Comparable {
        public Integer major, minor, patch;
        public String prefix, prerelease, meta;
        public Version(String v) {
            String regex = "([a-zA-Z]*)([0-9]+)?(\\.([0-9]+))?(\\.([0-9]+))?(-([.0-9A-Za-z-]+))?(\\+([.0-9A-Za-z-]+))?";
            Matcher matcher = Pattern.compile(regex).matcher(v);
            if (!matcher.find()) throw new IllegalArgumentException("version string is invalid");
            prefix      = (matcher.group(1) != null) ? matcher.group(1) : null;
            major       = (matcher.group(3) != null) ? Integer.valueOf(matcher.group(3)) : null;
            minor       = (matcher.group(5) != null) ? Integer.valueOf(matcher.group(5)) : null;
            patch       = (matcher.group(7) != null) ? Integer.valueOf(matcher.group(7)) : null;
            prerelease  = (matcher.group(9) != null) ? matcher.group(9) : null;
            meta        = (matcher.group(11) != null) ? matcher.group(11) : null;
        }
        public String toString() {
            return prefix + ((major!=null)?major:"") + ((minor!=null)?"."+minor:"") + ((patch!=null)?"."+patch:"") + ((prerelease!=null)?"-"+prerelease:"") + ((meta!=null)?"+"+meta:"");
        }
        public static String prefix(String v) { return new Version(v).prefix; }
        // returns v1 on equality
        public static String max(String v1, String v2) {
            if (new Version(v2).compareTo(new Version(v1)) > 0) return v2; else return v1;
        }
        public static String incPrerelease(String v) {
            Version vv = new Version(v);
            if (vv.prerelease == null) {
                if (vv.patch!=null) vv.patch++; else if (vv.minor!=null) vv.minor++; else if (vv.major!=null) vv.major++;
                vv.prerelease = "0";
            } else {
                String[] pr = vv.prerelease.split("[.]");
                int i = pr.length-1;
                for (; i >= 0; i--) if (pr[i].matches("[0-9]+")) break;
                if (i == -1)
                    pr[i] = String.valueOf(Integer.parseInt(pr[i]) + 1);
                else
                    pr[pr.length-1] += ".0";
                vv.prerelease = String.join(".", pr);
            }
            return vv.toString();
        }

        @Override
        public int compareTo(Object o) {
            // see semver.org
            Version vv1 = this, vv2 = (Version) o;
            int cmp;

            cmp = vv2.prefix.compareTo(vv1.prefix);
            if (cmp != 0) return cmp;

            cmp = ((vv2.major==null)?-1:vv2.major) - ((vv1.major==null)?-1:vv1.major);
            if (cmp != 0) return cmp;
            cmp = ((vv2.minor==null)?-1:vv2.minor) - ((vv1.minor==null)?-1:vv1.minor);
            if (cmp != 0) return cmp;
            cmp = ((vv2.patch==null)?-1:vv2.patch) - ((vv1.patch==null)?-1:vv1.patch);
            if (cmp != 0) return cmp;

            String[] pr1 = vv1.prerelease.split("[.]"), pr2 = vv2.prerelease.split("[.]");
            for (int i = 0; i < Math.min(pr1.length, pr2.length); i++) {
                if (pr1[0].matches("[0-9]+") && pr2[0].matches("[0-9]+"))
                    cmp = Integer.parseInt(pr2[i]) - Integer.parseInt(pr1[i]);
                else
                    cmp = pr2[i].compareTo(pr1[i]);
                if (cmp != 0) return cmp;
            }
            cmp = pr2.length - pr1.length;
            if (cmp != 0) return cmp;

            return 0;
        }
    }
    public Response webhook(String payload, String signature) {
        JsonObject event = (JsonObject) JsonHelper.parse(payload);
        try {
            Map<String,Object> response_body = new HashMap<>();
            if (!event.isNull("repository")) {
                // github
                String url = event.getJsonObject("repository").getString("url");
                ResultSet rs = null;
                if (event.getString("ref").equals("refs/heads/master")) {
                    // simple commit
                    rs = dm.query("SELECT * FROM buildhooks WHERE (trigger,url)=(?,?)", "commit", url);
                } else if (event.getString("ref").startsWith("refs/tags/")) {
                    // release
                    rs = dm.query("SELECT * FROM buildhooks WHERE (trigger,url) IS (?,?)", "release", url);
                }
                while (rs.next()) { // do for every app
                    int app = rs.getInt("target_app");
                    String change = rs.getString("change");
                    ResultSet rsApp = dm.query("SELECT config FROM apps WHERE app=?", app);
                    rsApp.next();
                    Map<String, Object> versions = (Map<String, Object>) JsonHelper.toCollection(rsApp.getString("versions"));
                    List<String> versionsToBuild = new LinkedList<>();
                    // adding new version(s) to app
                    if (change.equals("sync")) {
                        String version = event.getString("ref").substring("refs/tags/".length());                         rs = dm.query("SELECT config FROM apps WHERE app=?", app);
                        Map<String,Object> ver = new HashMap<>();
                            Map<String,Object> env = new HashMap<>();
                            ver.put("env", env);
                                Map<String,Object> env_param = new HashMap<>();
                                env.put(url, env_param);
                                    env_param.put("sha", event.getJsonObject("head_commit").getString("id"));
                                    env_param.put("tag", version);
                        versions.put(version, ver);
                        versionsToBuild.add(version);
                    } else if (change.equals("commit") || change.equals("none")) {
                        // get latest versions of every configured prefix, e.g. v1…, latest, …
                        String[] prefixes = rs.getString("prefixes").split(";");
                        String[] latestVersions = new String[prefixes.length];
                        for (String v : versions.keySet()) { // find latest versions
                            Version vv = new Version(v);
                            int i = Arrays.asList(prefixes).indexOf(Version.prefix(v));
                            if (i != -1) {
                                if (latestVersions[i] == null)
                                    latestVersions[i] = v;
                                else
                                    latestVersions[i] = Version.max(latestVersions[i], v);
                            }
                        }
                        // increase version
                        if (change.equals("commit")) {
                            for (int i = 0; i < latestVersions.length; i++) {
                                latestVersions[i] = Version.incPrerelease(latestVersions[i]);
                                Map<String, Object> ver = new HashMap<>();
                                Map<String, Object> env = new HashMap<>();
                                ver.put("env", env);
                                Map<String, Object> env_param = new HashMap<>();
                                env.put(url, env_param);
                                env_param.put("sha", event.getJsonObject("head_commit").getString("id"));
                                versions.put(latestVersions[i], ver);
                            }
                        }
                        Collections.addAll(versionsToBuild, latestVersions);
                    }
                    dm.update("UPDATE apps SET versions=? WHERE app=?", JsonHelper.toString(versions), app);

                    // build versions
                    Response r;
                    for (String v : versionsToBuild) {
                        r = serviceRunner.path("build").request().post(buildPostEntity(app, v, versions));
                        Map<String,Object> result = new HashMap<>();
                        response_body.put(String.valueOf(app), result);
                            result.put("status", r.getStatus());
                            result.put("body", r.readEntity(String.class));
                    }
                }
                return Response.ok(JsonHelper.toString(response_body), "application/json").build();
            } else {
                // built
            }
            return Response.status(400).entity("request not understood, sry").build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
    private Entity buildPostEntity(int app, String version, Map<String,Object> versions) {
        Map<String,Object> config = new HashMap<>();
        versions.entrySet().stream()
            .filter(e ->
                Version.prefix(e.getKey()).equals(Version.prefix(version))
                && new Version(e.getKey()).compareTo(new Version(version)) < 0
            ).sorted(Comparator.comparing(e -> new Version(e.getKey()))
            ).forEachOrdered(e -> {
                merge(config, (Map) e.getValue());
            });
        config.put("app", app);
        config.put("version", version);
        return Entity.entity(JsonHelper.toString(config), "application/json");
    }
    private void merge(Map<String,Object> mInto, Map<String,Object> mFrom) {
        for (String key : mFrom.keySet()) {
            Object x1 = mInto.get(key), x2 = mFrom.get(key);
            if (x2 == null) {
                mInto.remove(key);
            } else if ((x1 instanceof Map) && (x2 instanceof Map)) {
                merge((Map) x1, (Map) x2);
            } else {
                mInto.put(key, x2);
            }
        }
    }
}
