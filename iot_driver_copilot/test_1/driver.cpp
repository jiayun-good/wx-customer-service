#include <iostream>
#include <cstdlib>
#include <string>
#include <sstream>
#include <map>
#include <vector>
#include <algorithm>
#include <cstring>
#include <cstdio>
#include <unistd.h>
#include <netinet/in.h>
#include <sys/socket.h>
#include <thread>

#define BUFFER_SIZE 8192

std::string html_escape(const std::string& data) {
    std::ostringstream oss;
    for (char c : data) {
        switch (c) {
            case '&': oss << "&amp;"; break;
            case '<': oss << "&lt;"; break;
            case '>': oss << "&gt;"; break;
            case '\"': oss << "&quot;"; break;
            case '\'': oss << "&#39;"; break;
            default: oss << c; break;
        }
    }
    return oss.str();
}

std::map<std::string, std::string> parse_query_params(const std::string& query) {
    std::map<std::string, std::string> params;
    std::stringstream ss(query);
    std::string param;
    while (std::getline(ss, param, '&')) {
        auto eq = param.find('=');
        if (eq != std::string::npos) {
            params[param.substr(0, eq)] = param.substr(eq+1);
        }
    }
    return params;
}

std::string get_env(const char* env_name, const char* default_val) {
    const char* val = std::getenv(env_name);
    return val ? val : default_val;
}

std::string read_request_line(int client_fd) {
    std::string line;
    char c;
    while (recv(client_fd, &c, 1, 0) > 0) {
        if (c == '\r') continue;
        if (c == '\n') break;
        line += c;
    }
    return line;
}

std::string read_headers(int client_fd, std::map<std::string, std::string>& headers) {
    std::string line, body = "";
    char c;
    int state = 0;
    while (true) {
        line.clear();
        while (recv(client_fd, &c, 1, 0) > 0) {
            if (c == '\r') continue;
            if (c == '\n') break;
            line += c;
        }
        if (line.empty()) break;
        auto pos = line.find(':');
        if (pos != std::string::npos) {
            std::string key = line.substr(0,pos);
            std::string val = line.substr(pos+1);
            while (!val.empty() && val[0] == ' ') val.erase(0,1);
            headers[key] = val;
        }
    }
    if (headers.count("Content-Length")) {
        int to_read = std::stoi(headers["Content-Length"]);
        while (to_read--) {
            if (recv(client_fd, &c, 1, 0) > 0) {
                body += c;
            }
        }
    }
    return body;
}

void send_response(int client_fd, const std::string& status, const std::string& content_type,
                   const std::string& body, const std::map<std::string, std::string>& extra_headers = {}) {
    std::ostringstream oss;
    oss << "HTTP/1.1 " << status << "\r\n"
        << "Content-Type: " << content_type << "\r\n"
        << "Content-Length: " << body.size() << "\r\n"
        << "Connection: close\r\n";
    for (const auto& h : extra_headers) {
        oss << h.first << ": " << h.second << "\r\n";
    }
    oss << "\r\n" << body;
    send(client_fd, oss.str().c_str(), oss.str().size(), 0);
}

std::string xml_data_response(const std::map<std::string, std::string>& params) {
    int limit = -1, offset = 0;
    if (params.count("limit"))  limit = std::stoi(params.at("limit"));
    if (params.count("offset")) offset = std::stoi(params.at("offset"));

    // Simulate XML data points, e.g., <data><point id="1" value="..."/></data>
    std::ostringstream xml;
    xml << "<data>";
    int num_points = 10; // Example
    int begin = offset, end = (limit > 0) ? std::min(offset+limit, num_points) : num_points;
    for (int i = begin; i < end; ++i) {
        xml << "<point id=\"" << i << "\" value=\""
            << "value" << i << "\"/>";
    }
    xml << "</data>";
    return xml.str();
}

std::string json_command_response(const std::string& action, bool accepted, const std::string& status) {
    std::ostringstream ss;
    ss << "{"
       << "\"command\":\"" << html_escape(action) << "\","
       << "\"accepted\":" << (accepted ? "true" : "false") << ","
       << "\"status\":\"" << html_escape(status) << "\""
       << "}";
    return ss.str();
}

void handle_get_data(int client_fd, const std::string& query) {
    auto params = parse_query_params(query);
    std::string xml = xml_data_response(params);
    send_response(client_fd, "200 OK", "application/xml", xml);
}

void handle_post_commands(int client_fd, const std::string& body) {
    // Assume XML or JSON in body, but for this example, we extract a simple <action>...</action> or "action":"..."
    std::string action;
    auto pos = body.find("action");
    if (pos != std::string::npos) {
        auto q1 = body.find_first_of(":>", pos);
        if (q1 != std::string::npos) {
            q1++;
            auto q2 = body.find_first_of(",<\"", q1);
            action = body.substr(q1, (q2 != std::string::npos ? q2-q1 : std::string::npos));
            action.erase(std::remove_if(action.begin(), action.end(), [](unsigned char c){return c=='"'||c==' '||c=='\n'||c=='\r';}), action.end());
        }
    }
    if (action.empty()) action = "unknown";
    std::string resp = json_command_response(action, true, "executed");
    send_response(client_fd, "200 OK", "application/json", resp);
}

void not_found(int client_fd, const std::string& msg="") {
    std::string body = "<html><body>404 Not Found";
    if (!msg.empty()) body += "<pre>" + html_escape(msg) + "</pre>";
    body += "</body></html>";
    send_response(client_fd, "404 Not Found", "text/html", body);
}

void method_not_allowed(int client_fd) {
    send_response(client_fd, "405 Method Not Allowed", "text/plain", "405 Method Not Allowed");
}

void handle_connection(int client_fd) {
    std::string request_line = read_request_line(client_fd);
    if (request_line.empty()) { close(client_fd); return; }
    std::istringstream iss(request_line);
    std::string method, path_query, protocol;
    iss >> method >> path_query >> protocol;
    std::string path, query;
    auto qmark = path_query.find('?');
    if (qmark != std::string::npos) {
        path = path_query.substr(0, qmark);
        query = path_query.substr(qmark+1);
    } else {
        path = path_query;
    }
    std::map<std::string, std::string> headers;
    std::string body = read_headers(client_fd, headers);

    if (method == "GET" && path == "/data") {
        handle_get_data(client_fd, query);
    } else if (method == "POST" && path == "/commands") {
        handle_post_commands(client_fd, body);
    } else if (method == "OPTIONS") {
        std::map<std::string, std::string> hdr = {{"Allow", "GET, POST, OPTIONS"}};
        send_response(client_fd, "204 No Content", "text/plain", "", hdr);
    } else {
        if (path == "/data" || path == "/commands")
            method_not_allowed(client_fd);
        else
            not_found(client_fd);
    }
    close(client_fd);
}

int main() {
    std::string device_ip   = get_env("DEVICE_IP",   "127.0.0.1");
    std::string server_host = get_env("SERVER_HOST", "0.0.0.0");
    int server_port         = std::stoi(get_env("SERVER_PORT", "8080"));

    int server_fd = socket(AF_INET, SOCK_STREAM, 0);
    if (server_fd < 0) { perror("socket"); return 1; }

    int opt = 1;
    setsockopt(server_fd, SOL_SOCKET, SO_REUSEADDR, &opt, sizeof(opt));

    sockaddr_in addr;
    addr.sin_family = AF_INET;
    addr.sin_port = htons(server_port);
    addr.sin_addr.s_addr = inet_addr(server_host.c_str());

    if (bind(server_fd, (sockaddr*)&addr, sizeof(addr)) < 0) {
        perror("bind"); close(server_fd); return 2;
    }
    if (listen(server_fd, 10) < 0) {
        perror("listen"); close(server_fd); return 3;
    }
    std::cout << "HTTP Server started on " << server_host << ":" << server_port << std::endl;

    while (true) {
        sockaddr_in client_addr;
        socklen_t client_len = sizeof(client_addr);
        int client_fd = accept(server_fd, (sockaddr*)&client_addr, &client_len);
        if (client_fd < 0) { perror("accept"); continue; }
        std::thread(handle_connection, client_fd).detach();
    }
    close(server_fd);
    return 0;
}