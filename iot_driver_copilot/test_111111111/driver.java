package shifu.driver;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

public class DeviceShifuDriver {

    private static final String DEFAULT_SERVER_HOST = "0.0.0.0";
    private static final int DEFAULT_SERVER_PORT = 8080;

    private static final String DEVICE_NAME = getEnv("DEVICE_NAME", "test111111111");
    private static final String DEVICE_MODEL = getEnv("DEVICE_MODEL", "test111111111");
    private static final String MANUFACTURER = getEnv("MANUFACTURER", "s'da");
    private static final String DEVICE_TYPE = getEnv("DEVICE_TYPE", "sda");

    private static final String SERVER_HOST = getEnv("SERVER_HOST", DEFAULT_SERVER_HOST);
    private static final int SERVER_PORT = getEnvInt("SERVER_PORT", DEFAULT_SERVER_PORT);

    // Simulated in-memory device datapoints
    private static final List<Map<String, Object>> deviceData = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(SERVER_HOST, SERVER_PORT), 0);
        server.createContext("/points", new PointsHandler());
        server.createContext("/commands", new CommandsHandler());
        server.setExecutor(null); // default executor
        System.out.println("DeviceShifuDriver HTTP server started at http://" + SERVER_HOST + ":" + SERVER_PORT);
        server.start();
    }

    // Util: Get environment variable with fallback
    private static String getEnv(String key, String defaultVal) {
        String v = System.getenv(key);
        return v == null || v.isEmpty() ? defaultVal : v;
    }

    private static int getEnvInt(String key, int defaultVal) {
        try {
            String v = System.getenv(key);
            if (v == null || v.isEmpty()) return defaultVal;
            return Integer.parseInt(v);
        } catch (Exception e) {
            return defaultVal;
        }
    }

    // Handler for GET /points
    static class PointsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }

            Map<String, String> queryParams = parseQuery(exchange.getRequestURI().getRawQuery());
            int page = 1, limit = 10;
            try {
                if (queryParams.containsKey("page")) {
                    page = Integer.parseInt(queryParams.get("page"));
                    if (page < 1) page = 1;
                }
                if (queryParams.containsKey("limit")) {
                    limit = Integer.parseInt(queryParams.get("limit"));
                    if (limit < 1) limit = 10;
                }
            } catch (Exception ignore) {}

            List<Map<String, Object>> data;
            synchronized (deviceData) {
                data = new ArrayList<>(deviceData);
            }
            int from = Math.min((page - 1) * limit, data.size());
            int to = Math.min(from + limit, data.size());
            List<Map<String, Object>> pageData = data.subList(from, to);

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("device_name", DEVICE_NAME);
            result.put("device_model", DEVICE_MODEL);
            result.put("manufacturer", MANUFACTURER);
            result.put("device_type", DEVICE_TYPE);
            result.put("page", page);
            result.put("limit", limit);
            result.put("total", data.size());
            result.put("data_points", pageData);

            String resp = toJson(result);
            respond(exchange, 200, resp, "application/json");
        }
    }

    // Handler for POST /commands
    static class CommandsHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "Method Not Allowed", "text/plain");
                return;
            }
            String contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            String body = readAll(exchange.getRequestBody());

            if (contentType != null && contentType.contains("application/json")) {
                Map<String, Object> cmd = parseJson(body);
                if (cmd == null) {
                    respond(exchange, 400, "{\"error\":\"Invalid JSON\"}", "application/json");
                    return;
                }
                Map<String, Object> result = executeDeviceCommand(cmd);
                respond(exchange, 200, toJson(result), "application/json");
                return;
            } else if (contentType != null && contentType.contains("application/xml")) {
                Map<String, Object> cmd = parseXml(body);
                if (cmd == null) {
                    respond(exchange, 400, "{\"error\":\"Invalid XML\"}", "application/json");
                    return;
                }
                Map<String, Object> result = executeDeviceCommand(cmd);
                respond(exchange, 200, toJson(result), "application/json");
                return;
            } else if (contentType != null && contentType.contains("text/csv")) {
                Map<String, Object> cmd = parseCsv(body);
                Map<String, Object> result = executeDeviceCommand(cmd);
                respond(exchange, 200, toJson(result), "application/json");
                return;
            } else if (contentType != null && contentType.contains("text/plain")) {
                Map<String, Object> cmd = new HashMap<>();
                cmd.put("plain_text", body);
                Map<String, Object> result = executeDeviceCommand(cmd);
                respond(exchange, 200, toJson(result), "application/json");
                return;
            } else if (contentType != null && contentType.contains("application/octet-stream")) {
                Map<String, Object> cmd = new HashMap<>();
                cmd.put("binary", Base64.getEncoder().encodeToString(body.getBytes(StandardCharsets.UTF_8)));
                Map<String, Object> result = executeDeviceCommand(cmd);
                respond(exchange, 200, toJson(result), "application/json");
                return;
            } else {
                respond(exchange, 415, "{\"error\":\"Unsupported Content-Type\"}", "application/json");
            }
        }
    }

    // Simulate command execution and update device data
    private static Map<String, Object> executeDeviceCommand(Map<String, Object> cmd) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command_received", cmd);
        result.put("timestamp", System.currentTimeMillis());
        // For demo, simply append to deviceData as a new "telemetry" sample
        Map<String, Object> telemetry = new LinkedHashMap<>();
        telemetry.put("sample_id", UUID.randomUUID().toString());
        telemetry.put("timestamp", System.currentTimeMillis());
        telemetry.putAll(cmd);
        synchronized (deviceData) {
            deviceData.add(0, telemetry);
            if (deviceData.size() > 1000) { // keep up to 1000
                deviceData.remove(deviceData.size() - 1);
            }
        }
        result.put("result", "success");
        return result;
    }

    // Util: Parse query string
    private static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isEmpty()) return map;
        for (String pair : query.split("&")) {
            int idx = pair.indexOf('=');
            if (idx > 0) {
                map.put(urlDecode(pair.substring(0, idx)), urlDecode(pair.substring(idx + 1)));
            } else {
                map.put(urlDecode(pair), "");
            }
        }
        return map;
    }

    private static String urlDecode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            return s;
        }
    }

    // Util: Read all InputStream
    private static String readAll(InputStream stream) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int len;
        while ((len = stream.read(buf)) != -1) {
            out.write(buf, 0, len);
        }
        return out.toString(StandardCharsets.UTF_8.name());
    }

    // Util: Respond HTTP
    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        Headers h = exchange.getResponseHeaders();
        h.set("Content-Type", contentType + "; charset=utf-8");
        exchange.sendResponseHeaders(status, body.getBytes(StandardCharsets.UTF_8).length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body.getBytes(StandardCharsets.UTF_8));
        }
    }

    // Util: Simple JSON serialization (no third-party libs)
    private static String toJson(Object obj) {
        if (obj == null) return "null";
        if (obj instanceof Map) {
            Map<?, ?> map = (Map<?, ?>) obj;
            StringBuilder sb = new StringBuilder();
            sb.append("{");
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!first) sb.append(",");
                sb.append(toJson(entry.getKey().toString()));
                sb.append(":");
                sb.append(toJson(entry.getValue()));
                first = false;
            }
            sb.append("}");
            return sb.toString();
        } else if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            StringBuilder sb = new StringBuilder();
            sb.append("[");
            boolean first = true;
            for (Object o : list) {
                if (!first) sb.append(",");
                sb.append(toJson(o));
                first = false;
            }
            sb.append("]");
            return sb.toString();
        } else if (obj instanceof String) {
            return "\"" + obj.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        } else if (obj instanceof Number || obj instanceof Boolean) {
            return obj.toString();
        } else {
            return "\"" + String.valueOf(obj) + "\"";
        }
    }

    // Util: Minimal JSON parsing for flat objects
    @SuppressWarnings("unchecked")
    private static Map<String, Object> parseJson(String json) {
        try {
            json = json.trim();
            if (json.startsWith("{") && json.endsWith("}")) {
                Map<String, Object> res = new LinkedHashMap<>();
                String main = json.substring(1, json.length() - 1).trim();
                // Only flat key:value pairs, no nesting
                String[] pairs = main.split(",(?=([^\"]*\"[^\"]*\")*[^\"]*$)");
                for (String pair : pairs) {
                    if (pair.trim().isEmpty()) continue;
                    String[] kv = pair.split(":", 2);
                    String key = kv[0].trim().replaceAll("^\"|\"$", "");
                    String value = kv.length > 1 ? kv[1].trim() : "";
                    if (value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                        value = value.replace("\\\"", "\"").replace("\\\\", "\\");
                        res.put(key, value);
                    } else if ("true".equalsIgnoreCase(value) || "false".equalsIgnoreCase(value)) {
                        res.put(key, Boolean.parseBoolean(value));
                    } else {
                        try {
                            if (value.contains(".")) {
                                res.put(key, Double.parseDouble(value));
                            } else {
                                res.put(key, Long.parseLong(value));
                            }
                        } catch (Exception e) {
                            res.put(key, value);
                        }
                    }
                }
                return res;
            }
        } catch (Exception ignore) {}
        return null;
    }

    // Util: Minimal XML parsing for flat objects
    private static Map<String, Object> parseXml(String xml) {
        try {
            DocumentBuilderFactory dbFactory = DocumentBuilderFactory.newInstance();
            DocumentBuilder dBuilder = dbFactory.newDocumentBuilder();
            StringReader reader = new StringReader(xml);
            InputSource inputSource = new InputSource(reader);
            Document doc = dBuilder.parse(inputSource);
            doc.getDocumentElement().normalize();
            Element root = doc.getDocumentElement();
            Map<String, Object> map = new LinkedHashMap<>();
            NodeList children = root.getChildNodes();
            for (int i = 0; i < children.getLength(); i++) {
                Node n = children.item(i);
                if (n.getNodeType() == Node.ELEMENT_NODE) {
                    map.put(n.getNodeName(), n.getTextContent());
                }
            }
            return map;
        } catch (Exception e) {
            return null;
        }
    }

    // Util: Minimal CSV parsing (first row: keys, second row: values)
    private static Map<String, Object> parseCsv(String csv) {
        String[] lines = csv.trim().split("\n");
        if (lines.length < 2) return new HashMap<>();
        String[] keys = lines[0].trim().split(",");
        String[] values = lines[1].trim().split(",");
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keys.length && i < values.length; i++) {
            map.put(keys[i].trim(), values[i].trim());
        }
        return map;
    }
}