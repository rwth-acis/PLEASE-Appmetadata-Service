package i5.las2peer.services.appService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.json.JsonObject;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.client.Entity;
import javax.ws.rs.client.WebTarget;
import javax.ws.rs.core.Response;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
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
    String pleaseSecret;

    public WebhookHelper(DatabaseManager dm, String serviceRunnerUrl) {
        this.dm=dm;
        serviceRunner = ClientBuilder.newClient().target(serviceRunnerUrl);
        pleaseSecret = System.getenv("PLEASE_SECRET");
        if (pleaseSecret == null) {
            l.warn("no env variable PLEASE_SECRET exists, using default secret (insecure)");
            pleaseSecret = "abcdef123456";
        }
    }

    private static class Version implements Comparable {
        public Integer major, minor, patch;
        public String prefix, prerelease, meta;
        public Version(String v) {
            String regex = "([a-zA-Z]*)([0-9]+)?(\\.([0-9]+))?(\\.([0-9]+))?(-([.0-9A-Za-z-]+))?(\\+([.0-9A-Za-z-]+))?";
            Matcher matcher = Pattern.compile(regex).matcher(v);
            if (!matcher.find()) throw new IllegalArgumentException("version string is invalid");
            prefix      = (matcher.group(1) != null) ? matcher.group(1) : "";
            major       = (matcher.group(2) != null) ? Integer.valueOf(matcher.group(2)) : -1;
            minor       = (matcher.group(4) != null) ? Integer.valueOf(matcher.group(4)) : -1;
            patch       = (matcher.group(6) != null) ? Integer.valueOf(matcher.group(6)) : -1;
            prerelease  = (matcher.group(8) != null) ? matcher.group(8) : null;
            meta        = (matcher.group(10) != null) ? matcher.group(10) : null;
        }
        public String toString() {
            return prefix + ((major>-1)?major:"") + ((minor>-1)?"."+minor:"") + ((patch>-1)?"."+patch:"") + ((prerelease!=null)?"-"+prerelease:"") + ((meta!=null)?"+"+meta:"");
        }
        public static String prefix(String v) { return new Version(v).prefix; }
        // returns v1 on equality
        public static String max(String v1, String v2) {
            if (new Version(v2).compareTo(new Version(v1)) > 0) return v2; else return v1;
        }
        public static String incPrerelease(String v) {
            Version vv = new Version(v);
            if (vv.prerelease == null) {
                if (vv.patch>-1) vv.patch++; else if (vv.minor>-1) vv.minor++; else if (vv.major>-1) vv.major++;
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

            cmp = vv1.prefix.compareTo(vv2.prefix);
            if (cmp != 0) return cmp;

            cmp = vv1.major - vv2.major;
            if (cmp != 0) return cmp;
            cmp = vv1.minor - vv2.minor;
            if (cmp != 0) return cmp;
            cmp = vv1.patch - vv2.patch;
            if (cmp != 0) return cmp;

            cmp = ((vv1.prerelease == null) ? 1:0) - ((vv2.prerelease == null) ? 1:0);
            if (cmp != 0) return cmp;

            if (vv2.prerelease != null) {
                String[] pr1 = vv1.prerelease.split("[.]"), pr2 = vv2.prerelease.split("[.]");
                for (int i = 0; i < Math.min(pr1.length, pr2.length); i++) {
                    if (pr1[0].matches("[0-9]+") && pr2[0].matches("[0-9]+"))
                        cmp = Integer.parseInt(pr1[i]) - Integer.parseInt(pr2[i]);
                    else
                        cmp = pr1[i].compareTo(pr2[i]);
                    if (cmp != 0) return cmp;
                }
                cmp = pr1.length - pr2.length;
                if (cmp != 0) return cmp;
            }

            return 0;
        }
    }
    public Response webhook(String payload, String signature) {
        //TODO include signature check
        JsonObject event = (JsonObject) JsonHelper.parse(payload);
        try {
            if (event.containsKey("repository")) {
                // github
                //ResultSet rs = dm.query("SELECT secret FROM githubwebhooksecrets WHERE repo=?", event.getJsonObject("repository").getString("url"));
                //if (!rs.next())
                //    return Response.status(404).entity("no secret registered for this repo").build();
                //String secret = rs.getString(1);
                //if (!verifySignature(payload, secret, signature))
                //    return Response.status(400).entity("bad signature").build();

                return buildhook(event);
            } else if (event.containsKey("app")) {
                // build hook
                return deployhook(event);
            }
            return Response.status(400).entity("request not understood, sry").build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
    private boolean verifySignature(String payload, String secret, String signature) {
        try {
            Mac m = Mac.getInstance("HmacSHA1");
            SecretKeySpec key = new SecretKeySpec(secret.getBytes(), "HmacSHA1");
            m.init(key);
            byte[] digest = m.doFinal(payload.getBytes());
            return signature.startsWith("sha1=") && Arrays.equals(digest, signature.substring("sha1=".length()).getBytes());
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return false;
    }

    private Response buildhook(JsonObject event) throws SQLException {
        Map<String,Object> response_body = new HashMap<>();
        String url = event.getJsonObject("repository").getString("url");
        ResultSet rs = null;
        if (event.getString("ref").equals("refs/heads/master")) {
            // simple commit
            rs = dm.query("SELECT * FROM buildhooks WHERE (trigger,url)=(?,?)", "commit", url);
        } else if (event.getString("ref").startsWith("refs/tags/")) {
            // release
            rs = dm.query("SELECT * FROM buildhooks WHERE (trigger,url) IS (?,?)", "release", url);
        } else
            return Response.status(400).entity("invalid ref <"+event.getString("ref")+">").build();
        while (rs.next()) { // do for every app
            int app = rs.getInt("target_app");
            String change = rs.getString("change");
            ResultSet rsApp = dm.query("SELECT versions FROM apps WHERE app=?", app);
            rsApp.next();
            Map<String, Object> versions = (Map<String, Object>) JsonHelper.toCollection(rsApp.getString("versions"));
            List<String> versionsToBuild = new LinkedList<>();
            // adding new version(s) to app
            if (change.equals("sync")) {
                String version = event.getString("ref").substring("refs/tags/".length());
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
                        env_param.put("tag", null);
                        versions.put(latestVersions[i], ver);
                    }
                }
                Collections.addAll(versionsToBuild, latestVersions);
            }
            dm.update("UPDATE apps SET versions=? WHERE app=?", JsonHelper.toString(versions), app);

            // build versions
            Response r;
            for (String v : versionsToBuild) {
                Map<String,Object> config = mergedConfig(app, v, versions);
                Map<String,Object> reqBody = new HashMap<>();
                reqBody.put("app", app);
                reqBody.put("version", v);
                if(config.containsKey("env"))
                    reqBody.put("env", config.get("env"));
                if(config.containsKey("build")) {
                    if (((Map) config.get("build")).containsKey("base"))
                        reqBody.put("base", ((Map) config.get("build")).get("base"));
                    if (((Map) config.get("build")).containsKey("full"))
                        reqBody.put("full", ((Map) config.get("build")).get("full"));
                }
                r = serviceRunner.path("build").request().post(Entity.entity(JsonHelper.toString(reqBody), "application/json"));
                Map<String,Object> result = new HashMap<>();
                response_body.put(app+","+v, result);
                    result.put("status", r.getStatus());
                    result.put("body", r.readEntity(String.class));
            }
        }
        return Response.ok(JsonHelper.toString(response_body), "application/json").build();
    }
    private Map<String,Object> mergedConfig(int app, String version, Map<String,Object> versions) {
        Map<String,Object> config = new HashMap<>();
        versions.entrySet().stream()
            .filter(e ->
                Version.prefix(e.getKey()).equals(Version.prefix(version))
                && new Version(e.getKey()).compareTo(new Version(version)) <= 0
            ).sorted(Comparator.comparing(e -> new Version(e.getKey()))
            ).forEachOrdered(e -> {
                merge(config, (Map) ((Map.Entry<String,Object>)e).getValue());
            });
        return config;
    }
    private void merge(Map<String,Object> mInto, Map<String,Object> mFrom) {
        for (String key : mFrom.keySet()) {
            Object x1 = mInto.get(key), x2 = mFrom.get(key);
            if (x2 == null) {
                mInto.remove(key);
            } else if (x2 instanceof Map) {
                // don't simply assign x2 to remove null values in x2
                if (!(x1 instanceof Map)) mInto.put(key, (x1 = new HashMap<>()));
                merge((Map) x1, (Map) x2);
            } else {
                mInto.put(key, x2);
            }
        }
    }

    private Response deployhook(JsonObject event) throws SQLException {
        Map<String,Object> response_body = new HashMap<>();
        ResultSet rs = dm.query("SELECT * FROM deployhooks WHERE app=?", event.getInt("app"));
        while (rs.next()) {
            int app = rs.getInt("app");
            int iid = rs.getInt("target_iid");
            String triggers = rs.getString("triggers");
            Response r = serviceRunner.path("deployed/"+iid).request().get();
            JsonObject deploy = (JsonObject) JsonHelper.parse(r.readEntity(String.class));
            assert app == deploy.getInt("app") : "iid <"+iid+"> does not correspond to app <"+app+">";

            Version build_v = new Version(event.getString("version"));
            long build_i = event.getJsonNumber("buildid").longValue();
            Version dep_v = new Version(deploy.getString("version"));
            long dep_i = deploy.getJsonNumber("buildid").longValue();
            if (build_v.prefix.equals(dep_v.prefix)) {
                boolean triggered = false;
                triggered = triggered || (triggers.contains("build")
                        && build_v.compareTo(dep_v) == 0
                        && build_i > dep_i);
                triggered = triggered || (triggers.contains("commit")
                        && build_v.compareTo(dep_v) > 0
                        && build_v.major.equals(dep_v.major)
                        && build_v.minor.equals(dep_v.minor)
                        && build_v.patch.equals(dep_v.patch));
                triggered = triggered || (triggers.contains("patch")
                        && build_v.major.equals(dep_v.major)
                        && build_v.minor.equals(dep_v.minor)
                        && build_v.patch > dep_v.patch);
                triggered = triggered || (triggers.contains("minor")
                        && build_v.major.equals(dep_v.major)
                        && build_v.minor > dep_v.minor);
                triggered = triggered || (triggers.contains("major")
                        && build_v.major > dep_v.major);

                if (triggered) {
                    ResultSet rsApp = dm.query("SELECT versions FROM apps WHERE app=?", app);
                    rsApp.next();
                    Map<String,Object> versions = (Map<String, Object>) JsonHelper.toCollection(rsApp.getString("versions"));
                    Map<String,Object> config = mergedConfig(app, build_v.toString(), versions);
                    final Map EMPTY = new HashMap();
                    Map config_deploy_service = (Map) ((Map)config.getOrDefault("deploy", EMPTY)).getOrDefault("service", EMPTY);
                    Map<String,Object> reqBody = new HashMap<>();
                    reqBody.put("app", app);
                    reqBody.put("version", build_v.toString());
                    if(config.containsKey("env"))
                        reqBody.put("env", config.get("env"));
                    if(config_deploy_service.containsKey("base"))
                        reqBody.put("base", config_deploy_service.get("base"));
                    if(config_deploy_service.containsKey("command"))
                        reqBody.put("command", config_deploy_service.get("command"));
                    try {
                        r = serviceRunner.path("deployed/"+iid).request().header("Authorization","Basic "+ Base64.getEncoder().encodeToString(("appmetadata:"+pleaseSecret).getBytes("utf8")))
                            .put(Entity.entity(JsonHelper.toString(reqBody), "application/json"));
                        Map<String,Object> result = new HashMap<>();
                        response_body.put(iid+"", result);
                        result.put("status", r.getStatus());
                        result.put("body", r.readEntity(String.class));
                    } catch (UnsupportedEncodingException e) {
                        StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
                    }
                }
            }
        }
        return Response.ok(JsonHelper.toString(response_body), "application/json").build();
    }

    public Response registerHook(int iid, String triggers) {
        //TODO authentication
        try {
            Response r = serviceRunner.path("deployed/"+iid).request().get();
            if (r.getStatus() == 404) return Response.status(404).entity("iid <"+iid+"> not found").build();
            int app = ((JsonObject) JsonHelper.parse(r.readEntity(String.class))).getInt("app");
            dm.update("DELETE FROM deployhooks WHERE target_iid=?", iid);
            dm.update("INSERT INTO deployhooks VALUES (?,?,?)", app, triggers, iid);
            return Response.ok().build();
        } catch (SQLException e) {
            StringWriter sw = new StringWriter();e.printStackTrace(new PrintWriter(sw));l.error(sw.toString());
        }
        return Response.serverError().build();
    }
}
