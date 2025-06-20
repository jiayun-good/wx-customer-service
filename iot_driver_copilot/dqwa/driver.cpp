#include <iostream>
#include <cstdlib>
#include <string>
#include <sstream>
#include <vector>
#include <map>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <algorithm>
#include <cstring>
#include <cstdio>
#include <csignal>
#include <unistd.h>
#include <netinet/in.h>
#include <sys/socket.h>

// ========== Utility Functions ==========

std::string getenv_default(const char* var, const std::string& fallback) {
    const char* val = std::getenv(var);
    return val ? std::string(val) : fallback;
}

// Very basic URL decode
std::string urldecode(const std::string& str) {
    std::string ret;
    char ch;
    int i, ii;
    for (i=0; i<str.length(); i++) {
        if (int(str[i])==37) {
            sscanf(str.substr(i+1,2).c_str(), "%x", &ii);
            ch=static_cast<char>(ii);
            ret+=ch;
            i=i+2;
        } else {
            ret+=str[i];
        }
    }
    return ret;
}

// Simple query string parser
std::map<std::string, std::string> parse_query(const std::string& query) {
    std::map<std::string, std::string> result;
    std::istringstream ss(query);
    std::string item;
    while (std::getline(ss, item, '&')) {
        auto pos = item.find('=');
        if (pos != std::string::npos) {
            result[urldecode(item.substr(0, pos))] = urldecode(item.substr(pos+1));
        } else {
            result[urldecode(item)] = "";
        }
    }
    return result;
}

// ========== XML-to-JSON Conversion (very basic, for demo) ==========

// For real use, consider a proper XML parser.
// This is just for demonstration, expects XML of the form:
// <root><point1>val1</point1><point2>val2</point2>...</root>
std::string xml_to_json(const std::string& xml) {
    std::string json = "{";
    size_t pos = 0;
    while (true) {
        size_t start = xml.find('<', pos);
        if (start == std::string::npos) break;
        size_t end = xml.find('>', start+1);
        if (end == std::string::npos) break;
        std::string tag = xml.substr(start+1, end-start-1);
        if (tag[0] == '/') { pos = end+1; continue; } // skip closing tag
        size_t close = xml.find("</" + tag + ">", end+1);
        if (close == std::string::npos) break;
        std::string val = xml.substr(end+1, close-end-1);
        if (json.length() > 1) json += ",";
        json += "\"" + tag + "\":\"" + val + "\"";
        pos = close + tag.length() + 3;
    }
    json += "}";
    return json;
}

// ========== Minimal HTTP Server ==========

#define BUFFER_SIZE 8192

void send_response(int client_sock, const std::string& status, const std::string& content_type, const std::string& body) {
    std::ostringstream oss;
    oss << "HTTP/1.1 " << status << "\r\n";
    oss << "Content-Type: " << content_type << "\r\n";
    oss << "Content-Length: " << body.size() << "\r\n";
    oss << "Connection: close\r\n";
    oss << "\r\n";
    oss << body;
    std::string msg = oss.str();
    send(client_sock, msg.c_str(), msg.size(), 0);
}

void send_json(int client_sock, const std::string& json) {
    send_response(client_sock, "200 OK", "application/json", json);
}

void send_error(int client_sock, int code, const std::string& msg) {
    std::ostringstream oss;
    oss << "{ \"error\": \"" << msg << "\" }";
    send_response(client_sock, std::to_string(code) + " Error", "application/json", oss.str());
}

// ========== Device Communication (Simulated) ==========

// For demo: simulating device XML data and command response.
// In real driver, this should connect to the real device using its protocol.

std::mutex g_device_mutex;
std::string g_last_command = "";

std::string simulate_device_get_data(int page, int limit) {
    // Simulate XML output
    std::ostringstream oss;
    oss << "<root>";
    for (int i=0; i<limit; ++i) {
        int id = (page-1)*limit + i + 1;
        oss << "<datapoint" << id << ">value" << id << "</datapoint" << id << ">";
    }
    oss << "</root>";
    return oss.str();
}

std::string simulate_device_send_command(const std::string& cmd) {
    std::lock_guard<std::mutex> lock(g_device_mutex);
    g_last_command = cmd;
    return "<result>success</result>";
}

// ========== HTTP Handler Logic ==========

void handle_get_data(int client_sock, const std::map<std::string, std::string>& query) {
    int page = 1, limit = 10;
    if (query.count("page")) page = std::max(1, std::stoi(query.at("page")));
    if (query.count("limit")) limit = std::max(1, std::stoi(query.at("limit")));
    std::string xml_data = simulate_device_get_data(page, limit);
    std::string json_data = xml_to_json(xml_data);
    send_json(client_sock, json_data);
}

void handle_post_cmd(int client_sock, const std::string& body) {
    // Expect JSON: { "command": "restart" }
    std::string cmd;
    size_t pos = body.find("\"command\"");
    if (pos != std::string::npos) {
        size_t colon = body.find(':', pos);
        size_t quote1 = body.find('"', colon);
        size_t quote2 = body.find('"', quote1+1);
        if (quote1!=std::string::npos && quote2!=std::string::npos && quote2>quote1)
            cmd = body.substr(quote1+1, quote2-quote1-1);
    }
    if (cmd.empty()) {
        send_error(client_sock, 400, "Missing or invalid 'command'");
        return;
    }
    std::string xml_result = simulate_device_send_command(cmd);
    std::string json_result = xml_to_json(xml_result);
    send_json(client_sock, json_result);
}

// ========== Request Routing ==========

void handle_client(int client_sock) {
    char buffer[BUFFER_SIZE];
    ssize_t received = recv(client_sock, buffer, BUFFER_SIZE-1, 0);
    if (received <= 0) {
        close(client_sock);
        return;
    }
    buffer[received] = 0;
    std::string req(buffer);

    // Parse request line
    std::istringstream iss(req);
    std::string method, url, httpver;
    iss >> method >> url >> httpver;

    // Parse path and query
    std::string path = url, querystr;
    size_t qpos = url.find('?');
    if (qpos != std::string::npos) {
        path = url.substr(0, qpos);
        querystr = url.substr(qpos+1);
    }
    std::map<std::string, std::string> query;
    if (!querystr.empty()) query = parse_query(querystr);

    // Read body for POST
    std::string body;
    size_t body_start = req.find("\r\n\r\n");
    if (body_start != std::string::npos) {
        body = req.substr(body_start+4);
    }

    // Route
    if (method == "GET" && path == "/data") {
        handle_get_data(client_sock, query);
    } else if (method == "POST" && path == "/cmd") {
        handle_post_cmd(client_sock, body);
    } else {
        send_error(client_sock, 404, "Not found");
    }
    close(client_sock);
}

// ========== Main Server ==========

volatile bool g_running = true;

void signal_handler(int sig) { g_running = false; }

int main() {
    // --- Config from env ---
    std::string server_host = getenv_default("SHIFU_HTTP_SERVER_HOST", "0.0.0.0");
    int server_port = std::stoi(getenv_default("SHIFU_HTTP_SERVER_PORT", "8080"));

    // --- Setup server socket ---
    int server_sock = socket(AF_INET, SOCK_STREAM, 0);
    if (server_sock < 0) {
        std::cerr << "Socket error\n";
        return 1;
    }
    int opt = 1;
    setsockopt(server_sock, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr;
    std::memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_addr.s_addr = INADDR_ANY;
    addr.sin_port = htons(server_port);

    if (bind(server_sock, (struct sockaddr*)&addr, sizeof(addr)) < 0) {
        std::cerr << "Bind error\n";
        close(server_sock);
        return 2;
    }
    if (listen(server_sock, 8) < 0) {
        std::cerr << "Listen error\n";
        close(server_sock);
        return 3;
    }

    std::signal(SIGINT, signal_handler);
    std::cout << "HTTP server listening on " << server_host << ":" << server_port << std::endl;

    while (g_running) {
        sockaddr_in client_addr;
        socklen_t ca_len = sizeof(client_addr);
        int client_sock = accept(server_sock, (struct sockaddr*)&client_addr, &ca_len);
        if (client_sock < 0) {
            if (!g_running) break;
            continue;
        }
        std::thread([client_sock]() {
            handle_client(client_sock);
        }).detach();
    }
    close(server_sock);
    return 0;
}