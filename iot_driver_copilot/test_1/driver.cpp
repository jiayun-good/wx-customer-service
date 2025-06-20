#include <iostream>
#include <sstream>
#include <string>
#include <cstdlib>
#include <map>
#include <vector>
#include <algorithm>
#include <cstring>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <netinet/in.h>
#include <unistd.h>
#include <sys/socket.h>

// Utility for environment variable with default
std::string get_env(const std::string& var, const std::string& def) {
    const char* val = std::getenv(var.c_str());
    return val ? std::string(val) : def;
}

// XML Data Simulation (Replace with your actual device query logic)
std::string get_device_xml_data(const std::string& filter, int limit, int offset) {
    std::ostringstream oss;
    oss << "<dataPoints>";
    for (int i = 0; i < limit; ++i) {
        int idx = offset + i;
        oss << "<dataPoint>";
        oss << "<id>" << idx << "</id>";
        oss << "<name>DataPoint" << idx << "</name>";
        oss << "<value>" << (idx * 10) << "</value>";
        oss << "</dataPoint>";
    }
    oss << "</dataPoints>";
    return oss.str();
}

// Parse query parameters from URL
std::map<std::string, std::string> parse_query(const std::string& query) {
    std::map<std::string, std::string> params;
    std::string::size_type last = 0, next = 0;
    while ((next = query.find('&', last)) != std::string::npos) {
        std::string pair = query.substr(last, next - last);
        auto eq = pair.find('=');
        if (eq != std::string::npos)
            params[pair.substr(0, eq)] = pair.substr(eq + 1);
        last = next + 1;
    }
    std::string pair = query.substr(last);
    auto eq = pair.find('=');
    if (eq != std::string::npos)
        params[pair.substr(0, eq)] = pair.substr(eq + 1);
    return params;
}

// Minimal JSON builder (for command response)
std::string build_json_response(const std::string& status, const std::string& msg) {
    std::ostringstream oss;
    oss << "{";
    oss << "\"status\":\"" << status << "\",";
    oss << "\"message\":\"" << msg << "\"";
    oss << "}";
    return oss.str();
}

// Parse HTTP request line and headers
struct HttpRequest {
    std::string method;
    std::string path;
    std::string query;
    std::string httpver;
    std::map<std::string, std::string> headers;
    std::string body;
};

bool parse_http_request(const std::string& raw, HttpRequest& req) {
    std::istringstream iss(raw);
    std::string line;
    if (!std::getline(iss, line)) return false;
    std::istringstream first(line);
    if (!(first >> req.method >> req.path >> req.httpver)) return false;

    // Split path and query
    auto qpos = req.path.find('?');
    if (qpos != std::string::npos) {
        req.query = req.path.substr(qpos + 1);
        req.path = req.path.substr(0, qpos);
    }

    // Headers
    while (std::getline(iss, line) && line != "\r") {
        if (line.back() == '\r') line.pop_back();
        auto colon = line.find(':');
        if (colon != std::string::npos) {
            std::string key = line.substr(0, colon);
            while (!key.empty() && key.back() == ' ') key.pop_back();
            std::string value = line.substr(colon + 1);
            while (!value.empty() && value.front() == ' ') value.erase(0, 1);
            req.headers[key] = value;
        }
    }

    // Body (if any)
    std::ostringstream bodyoss;
    while (std::getline(iss, line)) bodyoss << line << "\n";
    req.body = bodyoss.str();
    if (!req.body.empty() && req.body.back() == '\n') req.body.pop_back();
    return true;
}

// HTTP Response helpers
void send_404(int client) {
    std::string resp = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: 9\r\n\r\nNot Found";
    send(client, resp.c_str(), resp.size(), 0);
}

void send_405(int client) {
    std::string resp = "HTTP/1.1 405 Method Not Allowed\r\nContent-Type: text/plain\r\nContent-Length: 18\r\n\r\nMethod Not Allowed";
    send(client, resp.c_str(), resp.size(), 0);
}

void send_400(int client, const std::string& msg) {
    std::ostringstream oss;
    oss << "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: " << msg.size() << "\r\n\r\n" << msg;
    std::string resp = oss.str();
    send(client, resp.c_str(), resp.size(), 0);
}

void send_xml(int client, const std::string& xml) {
    std::ostringstream oss;
    oss << "HTTP/1.1 200 OK\r\nContent-Type: application/xml\r\nContent-Length: " << xml.size() << "\r\n\r\n" << xml;
    std::string resp = oss.str();
    send(client, resp.c_str(), resp.size(), 0);
}

void send_json(int client, const std::string& json) {
    std::ostringstream oss;
    oss << "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: " << json.size() << "\r\n\r\n" << json;
    std::string resp = oss.str();
    send(client, resp.c_str(), resp.size(), 0);
}

// Handle /data endpoint (GET)
void handle_get_data(int client, const HttpRequest& req) {
    auto params = parse_query(req.query);

    std::string filter = params.count("filter") ? params["filter"] : "";
    int limit = params.count("limit") ? std::stoi(params["limit"]) : 10;
    int offset = params.count("offset") ? std::stoi(params["offset"]) : 0;

    std::string xml = get_device_xml_data(filter, limit, offset);
    send_xml(client, xml);
}

// Handle /commands endpoint (POST)
void handle_post_command(int client, const HttpRequest& req) {
    // Here, you would parse the XML/JSON payload and execute the command.
    // For demonstration, we simulate command acceptance.
    std::string resp = build_json_response("accepted", "Command received and executed.");
    send_json(client, resp);
}

// Main HTTP request handler
void handle_client(int client) {
    constexpr size_t BUF_SIZE = 8192;
    char buf[BUF_SIZE];
    ssize_t bytes = recv(client, buf, BUF_SIZE - 1, 0);
    if (bytes <= 0) {
        close(client);
        return;
    }
    buf[bytes] = '\0';

    HttpRequest req;
    if (!parse_http_request(buf, req)) {
        send_400(client, "Bad HTTP request");
        close(client);
        return;
    }

    if (req.method == "GET" && req.path == "/data") {
        handle_get_data(client, req);
    } else if (req.method == "POST" && req.path == "/commands") {
        handle_post_command(client, req);
    } else if ( (req.method == "GET" && req.path == "/") || (req.method == "GET" && req.path == "/healthz")) {
        std::string resp = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: 2\r\n\r\nOK";
        send(client, resp.c_str(), resp.size(), 0);
    } else if (req.method != "GET" && req.method != "POST") {
        send_405(client);
    } else {
        send_404(client);
    }
    close(client);
}

int main() {
    std::string server_host = get_env("SHIFU_HTTP_SERVER_HOST", "0.0.0.0");
    int server_port = std::stoi(get_env("SHIFU_HTTP_SERVER_PORT", "8080"));

    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd == -1) {
        std::cerr << "Cannot create socket\n";
        return 1;
    }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr{};
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = (server_host == "0.0.0.0") ? INADDR_ANY : inet_addr(server_host.c_str());
    addr.sin_port = htons(server_port);

    if (bind(server_fd, (sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "Bind failed\n";
        close(server_fd);
        return 1;
    }
    if (listen(server_fd, 20) < 0) {
        std::cerr << "Listen failed\n";
        close(server_fd);
        return 1;
    }

    std::cout << "HTTP server started at " << server_host << ":" << server_port << std::endl;

    while (true) {
        sockaddr_in client_addr{};
        socklen_t client_len = sizeof(client_addr);
        int client = accept(server_fd, (sockaddr*)&client_addr, &client_len);
        if (client < 0) continue;
        std::thread([client](){ handle_client(client); }).detach();
    }

    close(server_fd);
    return 0;
}