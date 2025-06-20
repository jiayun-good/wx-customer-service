#include <iostream>
#include <string>
#include <cstdlib>
#include <cstring>
#include <sstream>
#include <map>
#include <vector>
#include <algorithm>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <ctime>
#include <sys/types.h>
#include <sys/socket.h>
#include <netinet/in.h>
#include <unistd.h>

#define BUFFER_SIZE 8192

// Utility: Split string by delimiter
std::vector<std::string> split(const std::string& str, char delim) {
    std::vector<std::string> tokens;
    std::string tmp;
    std::istringstream ss(str);
    while (getline(ss, tmp, delim)) {
        tokens.push_back(tmp);
    }
    return tokens;
}

// Utility: Trim whitespace
std::string trim(const std::string& s) {
    size_t start = s.find_first_not_of(" \t\r\n");
    size_t end = s.find_last_not_of(" \t\r\n");
    if (start == std::string::npos) return "";
    return s.substr(start, end - start + 1);
}

// Parse query parameters from URI
std::map<std::string, std::string> parse_query(const std::string& uri) {
    std::map<std::string, std::string> params;
    auto qpos = uri.find('?');
    if (qpos == std::string::npos) return params;
    auto query = uri.substr(qpos + 1);
    auto kvs = split(query, '&');
    for (const auto& kv : kvs) {
        auto eq = kv.find('=');
        if (eq != std::string::npos) {
            params[kv.substr(0, eq)] = kv.substr(eq + 1);
        }
    }
    return params;
}

// Very simple HTTP request parser
struct HttpRequest {
    std::string method, path, http_version, body;
    std::map<std::string, std::string> headers;
    std::map<std::string, std::string> query_params;
};

// Simple HTTP response
struct HttpResponse {
    int code;
    std::string status;
    std::map<std::string, std::string> headers;
    std::string body;
};

// Generate HTTP date string
std::string http_date() {
    char buf[128];
    time_t now = time(0);
    struct tm tm = *gmtime(&now);
    strftime(buf, sizeof(buf), "%a, %d %b %Y %H:%M:%S GMT", &tm);
    return std::string(buf);
}

// Compose and send an HTTP response
void send_response(int client_sock, const HttpResponse& resp) {
    std::ostringstream oss;
    oss << "HTTP/1.1 " << resp.code << " " << resp.status << "\r\n";
    oss << "Date: " << http_date() << "\r\n";
    if (resp.headers.find("Content-Type") == resp.headers.end())
        oss << "Content-Type: text/plain\r\n";
    for (const auto& h : resp.headers) {
        oss << h.first << ": " << h.second << "\r\n";
    }
    oss << "Content-Length: " << resp.body.size() << "\r\n";
    oss << "Connection: close\r\n";
    oss << "\r\n";
    oss << resp.body;
    std::string out = oss.str();
    send(client_sock, out.c_str(), out.size(), 0);
}

// Parse HTTP request from socket
bool parse_http_request(int client_sock, HttpRequest& req) {
    char buffer[BUFFER_SIZE];
    std::string request;
    ssize_t bytes;
    // Read until \r\n\r\n
    while (request.find("\r\n\r\n") == std::string::npos) {
        bytes = recv(client_sock, buffer, sizeof(buffer), 0);
        if (bytes <= 0) return false;
        request.append(buffer, bytes);
        if (request.size() > 65536) return false;
    }

    // Split request into lines
    auto req_end = request.find("\r\n\r\n");
    std::string header_str = request.substr(0, req_end);
    std::string rest = request.substr(req_end + 4);

    std::vector<std::string> lines = split(header_str, '\n');
    if (lines.empty()) return false;
    // Parse request line
    auto req_parts = split(trim(lines[0]), ' ');
    if (req_parts.size() < 3) return false;
    req.method = req_parts[0];
    req.path = req_parts[1];
    req.http_version = req_parts[2];
    auto qpos = req.path.find('?');
    if (qpos != std::string::npos) {
        req.query_params = parse_query(req.path);
        req.path = req.path.substr(0, qpos);
    }
    // Parse headers
    for (size_t i = 1; i < lines.size(); ++i) {
        auto line = trim(lines[i]);
        if (line.empty() || line == "\r") continue;
        auto cpos = line.find(':');
        if (cpos != std::string::npos) {
            auto key = trim(line.substr(0, cpos));
            auto val = trim(line.substr(cpos + 1));
            std::transform(key.begin(), key.end(), key.begin(), ::tolower);
            req.headers[key] = val;
        }
    }
    // If POST, read body if needed
    if (req.method == "POST") {
        size_t content_length = 0;
        if (req.headers.find("content-length") != req.headers.end()) {
            content_length = std::stoi(req.headers["content-length"]);
        }
        if (rest.size() < content_length) {
            size_t remain = content_length - rest.size();
            while (remain > 0) {
                bytes = recv(client_sock, buffer, std::min(remain, sizeof(buffer)), 0);
                if (bytes <= 0) return false;
                rest.append(buffer, bytes);
                remain -= bytes;
            }
        }
        req.body = rest.substr(0, content_length);
    }
    return true;
}

// Simulate device data (XML)
std::string get_device_xml(const std::map<std::string, std::string>& qparams) {
    std::ostringstream oss;
    oss << "<DeviceData>\n";
    oss << "  <DeviceName>test1</DeviceName>\n";
    oss << "  <Model>dsa</Model>\n";
    oss << "  <Manufacturer>adsads</Manufacturer>\n";
    oss << "  <Type>ads</Type>\n";
    oss << "  <Time>" << std::time(nullptr) << "</Time>\n";
    // Optionally add query param info
    for (const auto& p : qparams) {
        oss << "  <QueryParam name=\"" << p.first << "\">" << p.second << "</QueryParam>\n";
    }
    oss << "</DeviceData>\n";
    return oss.str();
}

// Simulate command execution
std::string execute_command(const std::string& command) {
    // In real implementation, send command to device and get response.
    std::ostringstream oss;
    oss << "{";
    oss << "\"accepted\": true, ";
    oss << "\"executed\": true, ";
    oss << "\"command\": \"" << command << "\", ";
    oss << "\"timestamp\": " << std::time(nullptr);
    oss << "}";
    return oss.str();
}

// Main request handler
void handle_client(int client_sock) {
    HttpRequest req;
    if (!parse_http_request(client_sock, req)) {
        HttpResponse resp;
        resp.code = 400;
        resp.status = "Bad Request";
        resp.body = "Malformed HTTP request.\n";
        send_response(client_sock, resp);
        close(client_sock);
        return;
    }

    if (req.method == "GET" && req.path == "/data") {
        std::string xml = get_device_xml(req.query_params);
        HttpResponse resp;
        resp.code = 200;
        resp.status = "OK";
        resp.headers["Content-Type"] = "application/xml";
        resp.body = xml;
        send_response(client_sock, resp);

    } else if (req.method == "POST" && req.path == "/commands") {
        if (req.body.empty()) {
            HttpResponse resp;
            resp.code = 400;
            resp.status = "Bad Request";
            resp.body = "{\"error\": \"Missing command payload\"}";
            resp.headers["Content-Type"] = "application/json";
            send_response(client_sock, resp);
        } else {
            std::string result = execute_command(req.body);
            HttpResponse resp;
            resp.code = 200;
            resp.status = "OK";
            resp.headers["Content-Type"] = "application/json";
            resp.body = result;
            send_response(client_sock, resp);
        }
    } else {
        HttpResponse resp;
        resp.code = 404;
        resp.status = "Not Found";
        resp.body = "Not Found\n";
        send_response(client_sock, resp);
    }
    close(client_sock);
}

// Get env value or default
std::string getenv_or(const char* key, const char* defval) {
    const char* v = getenv(key);
    return v ? v : defval;
}

int main() {
    // Configurable host/port from env
    std::string server_host = getenv_or("DSHIFU_HTTP_SERVER_HOST", "0.0.0.0");
    int server_port = std::stoi(getenv_or("DSHIFU_HTTP_SERVER_PORT", "8080"));

    // Set up server
    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd == -1) {
        std::cerr << "Socket creation failed\n";
        return 1;
    }
    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = inet_addr(server_host.c_str());
    addr.sin_port = htons(server_port);

    if (bind(server_fd, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "Bind failed\n";
        close(server_fd);
        return 1;
    }

    if (listen(server_fd, 16) < 0) {
        std::cerr << "Listen failed\n";
        close(server_fd);
        return 1;
    }

    std::cout << "HTTP DeviceShifu driver running at " << server_host << ":" << server_port << std::endl;

    while (true) {
        sockaddr_in client_addr;
        socklen_t addrlen = sizeof(client_addr);
        int client_sock = accept(server_fd, (struct sockaddr*)&client_addr, &addrlen);
        if (client_sock < 0) continue;
        std::thread t(handle_client, client_sock);
        t.detach();
    }
    close(server_fd);
    return 0;
}