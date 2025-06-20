#include <iostream>
#include <cstdlib>
#include <string>
#include <vector>
#include <map>
#include <sstream>
#include <algorithm>
#include <thread>
#include <mutex>
#include <fstream>
#include <streambuf>
#include <cstring>
#include <cstdio>
#include <chrono>
#include <ctime>
#include <cctype>
#include <netinet/in.h>
#include <unistd.h>
#include <sys/socket.h>

#define BUFFER_SIZE 8192

// Utility functions
std::string urlDecode(const std::string& src) {
    std::string ret;
    char ch;
    int i, ii;
    for (i = 0; i < src.length(); i++) {
        if (int(src[i]) == 37) {
            sscanf(src.substr(i + 1, 2).c_str(), "%x", &ii);
            ch = static_cast<char>(ii);
            ret += ch;
            i = i + 2;
        } else {
            ret += src[i];
        }
    }
    return ret;
}

std::map<std::string, std::string> parseQueryString(const std::string& qs) {
    std::map<std::string, std::string> params;
    std::istringstream ss(qs);
    std::string pair;
    while (std::getline(ss, pair, '&')) {
        auto pos = pair.find('=');
        if (pos != std::string::npos) {
            std::string key = urlDecode(pair.substr(0, pos));
            std::string value = urlDecode(pair.substr(pos + 1));
            params[key] = value;
        }
    }
    return params;
}

struct HttpRequest {
    std::string method;
    std::string path;
    std::string query_string;
    std::map<std::string, std::string> headers;
    std::string body;
};

struct HttpResponse {
    int status_code;
    std::string status_text;
    std::map<std::string, std::string> headers;
    std::string body;
};

std::string currentTimeISO8601() {
    std::time_t t = std::time(nullptr);
    char buf[32];
    std::strftime(buf, sizeof(buf), "%FT%TZ", std::gmtime(&t));
    return buf;
}

// XML generator for data points (dummy data)
std::string generateXMLDataPoints(const std::map<std::string, std::string>& filters, int limit, int offset) {
    std::ostringstream xml;
    xml << "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";
    xml << "<dataPoints>\n";
    int count = 0;
    for (int i = 0; i < 10; ++i) {
        if (i < offset) continue;
        if (limit > 0 && count >= limit) break;
        xml << "  <data>\n";
        xml << "    <id>" << (i + 1) << "</id>\n";
        xml << "    <value>" << (100 + i) << "</value>\n";
        xml << "    <timestamp>" << currentTimeISO8601() << "</timestamp>\n";
        xml << "  </data>\n";
        ++count;
    }
    xml << "</dataPoints>\n";
    return xml.str();
}

// JSON response for commands (dummy execution)
std::string generateCommandResponse(const std::string& action, bool accepted) {
    std::ostringstream json;
    json << "{";
    json << "\"accepted\":" << (accepted ? "true" : "false");
    json << ",\"action\":\"" << action << "\"";
    json << ",\"status\":\"" << (accepted ? "executed" : "rejected") << "\"";
    json << ",\"timestamp\":\"" << currentTimeISO8601() << "\"";
    json << "}";
    return json.str();
}

// HTTP parser functions
bool readLine(int client_sock, std::string& line) {
    line.clear();
    char c;
    while (true) {
        ssize_t n = recv(client_sock, &c, 1, 0);
        if (n <= 0) return false;
        if (c == '\r') continue;
        if (c == '\n') break;
        line += c;
    }
    return true;
}

bool parseHttpRequest(int client_sock, HttpRequest& req) {
    std::string line;
    if (!readLine(client_sock, line)) return false;
    std::istringstream reqline(line);
    reqline >> req.method;
    std::string full_path;
    reqline >> full_path;
    size_t qpos = full_path.find('?');
    if (qpos != std::string::npos) {
        req.path = full_path.substr(0, qpos);
        req.query_string = full_path.substr(qpos + 1);
    } else {
        req.path = full_path;
        req.query_string.clear();
    }
    // Skip protocol version
    while (std::getline(reqline, line, ' '));
    // Headers
    req.headers.clear();
    while (readLine(client_sock, line) && !line.empty()) {
        auto pos = line.find(':');
        if (pos != std::string::npos) {
            std::string key = line.substr(0, pos);
            std::string val = line.substr(pos + 1);
            while (!val.empty() && std::isspace(val[0])) val.erase(0, 1);
            req.headers[key] = val;
        }
    }
    // Body
    req.body.clear();
    auto it = req.headers.find("Content-Length");
    if (it != req.headers.end()) {
        int content_length = std::stoi(it->second);
        char* buf = new char[content_length];
        int read_so_far = 0;
        while (read_so_far < content_length) {
            ssize_t n = recv(client_sock, buf + read_so_far, content_length - read_so_far, 0);
            if (n <= 0) break;
            read_so_far += n;
        }
        req.body.assign(buf, content_length);
        delete[] buf;
    }
    return true;
}

void sendHttpResponse(int client_sock, const HttpResponse& resp) {
    std::ostringstream oss;
    oss << "HTTP/1.1 " << resp.status_code << " " << resp.status_text << "\r\n";
    for (const auto& kv : resp.headers) {
        oss << kv.first << ": " << kv.second << "\r\n";
    }
    oss << "Content-Length: " << resp.body.size() << "\r\n";
    oss << "\r\n";
    oss << resp.body;
    std::string msg = oss.str();
    send(client_sock, msg.data(), msg.size(), 0);
}

void handleDataEndpoint(const HttpRequest& req, HttpResponse& resp) {
    // Query: filter, limit, offset
    std::map<std::string, std::string> qs = parseQueryString(req.query_string);
    int limit = 0, offset = 0;
    if (qs.count("limit")) limit = std::max(0, std::stoi(qs["limit"]));
    if (qs.count("offset")) offset = std::max(0, std::stoi(qs["offset"]));
    // filter is unused in dummy data
    resp.status_code = 200;
    resp.status_text = "OK";
    resp.headers["Content-Type"] = "application/xml";
    resp.body = generateXMLDataPoints(qs, limit, offset);
}

void handleCommandsEndpoint(const HttpRequest& req, HttpResponse& resp) {
    // Expect JSON or XML body, but we'll simply parse for "action"
    std::string action;
    size_t pos = req.body.find("\"action\"");
    if (pos != std::string::npos) {
        size_t v1 = req.body.find(':', pos);
        size_t v2 = req.body.find_first_of(",}", v1 + 1);
        if (v1 != std::string::npos && v2 != std::string::npos) {
            std::string val = req.body.substr(v1 + 1, v2 - v1 - 1);
            val.erase(std::remove(val.begin(), val.end(), '"'), val.end());
            val.erase(std::remove_if(val.begin(), val.end(), ::isspace), val.end());
            action = val;
        }
    } else {
        // Try as XML
        size_t x1 = req.body.find("<action>");
        size_t x2 = req.body.find("</action>");
        if (x1 != std::string::npos && x2 != std::string::npos && x2 > x1) {
            action = req.body.substr(x1 + 8, x2 - (x1 + 8));
        }
    }
    bool accepted = !action.empty();
    resp.status_code = accepted ? 200 : 400;
    resp.status_text = accepted ? "OK" : "Bad Request";
    resp.headers["Content-Type"] = "application/json";
    resp.body = generateCommandResponse(action, accepted);
}

void handleNotFound(HttpResponse& resp) {
    resp.status_code = 404;
    resp.status_text = "Not Found";
    resp.headers["Content-Type"] = "text/plain";
    resp.body = "404 Not Found";
}

void handleBadRequest(HttpResponse& resp) {
    resp.status_code = 400;
    resp.status_text = "Bad Request";
    resp.headers["Content-Type"] = "text/plain";
    resp.body = "400 Bad Request";
}

void handleClient(int client_sock) {
    HttpRequest req;
    HttpResponse resp;
    if (!parseHttpRequest(client_sock, req)) {
        handleBadRequest(resp);
        sendHttpResponse(client_sock, resp);
        close(client_sock);
        return;
    }
    if (req.method == "GET" && req.path == "/data") {
        handleDataEndpoint(req, resp);
    } else if (req.method == "POST" && req.path == "/commands") {
        handleCommandsEndpoint(req, resp);
    } else {
        handleNotFound(resp);
    }
    sendHttpResponse(client_sock, resp);
    close(client_sock);
}

int main() {
    // Read configuration from environment
    const char* env_host = std::getenv("SHIFU_HTTP_HOST");
    const char* env_port = std::getenv("SHIFU_HTTP_PORT");
    std::string host = env_host ? env_host : "0.0.0.0";
    int port = env_port ? std::stoi(env_port) : 8080;

    int server_sock = socket(AF_INET, SOCK_STREAM, 0);
    if (server_sock < 0) {
        std::cerr << "Failed to create socket\n";
        return 1;
    }
    int opt = 1;
    setsockopt(server_sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(port);
    addr.sin_addr.s_addr = (host == "0.0.0.0") ? INADDR_ANY : inet_addr(host.c_str());
    if (bind(server_sock, (sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "Failed to bind to " << host << ":" << port << "\n";
        close(server_sock);
        return 1;
    }
    if (listen(server_sock, 16) < 0) {
        std::cerr << "Failed to listen\n";
        close(server_sock);
        return 1;
    }
    std::cout << "HTTP server listening on " << host << ":" << port << std::endl;
    while (true) {
        sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_sock = accept(server_sock, (sockaddr*)&client_addr, &client_len);
        if (client_sock < 0) continue;
        std::thread(handleClient, client_sock).detach();
    }
    close(server_sock);
    return 0;
}