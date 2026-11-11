import os
import socket
import yaml

def get_host_ip():
    # 1. Проверяем явное значение из ENV
    env_ip = os.getenv("NODE_IP") or os.getenv("NODE0_IP")
    if env_ip:
        return env_ip

    # 2. Пробуем определить внешнее имя хоста
    try:
        hostname = socket.gethostname()
        ip = socket.gethostbyname(hostname)
        if ip and not ip.startswith("127."):
            return ip
    except Exception:
        pass

    # 3. Резервный сокет-метод
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        pass

    return "127.0.0.1"

default_ip = get_host_ip()

ip_node0 = os.getenv("NODE0_IP") or default_ip
ip_node1 = os.getenv("NODE1_IP") or default_ip
ip_node2 = os.getenv("NODE2_IP") or default_ip

nodes_data = [
    {
        "target_ip": ip_node0, "target_port": 50000,
        "coordinator_ip": ip_node0, "coordinator_port": 51000,
        "replicator_port": 52000, "stat_port": 8080, "cpu_pin_offset": 0, "rep_cpu_pin_offset": 8
    },
    {
        "target_ip": ip_node1, "target_port": 60000,
        "coordinator_ip": ip_node1, "coordinator_port": 61000,
        "replicator_port": 62000, "stat_port": 8081, "cpu_pin_offset": 4, "rep_cpu_pin_offset": 10
    },
    {
        "target_ip": ip_node2, "target_port": 20000,
        "coordinator_ip": ip_node2, "coordinator_port": 21000,
        "replicator_port": 22000, "stat_port": 8082, "cpu_pin_offset": 8, "rep_cpu_pin_offset": 12
    }
]

cluster_section = [
    {
        "target_ip": node["target_ip"],
        "target_port": node["target_port"],
        "coordinator_ip": node["coordinator_ip"],
        "coordinator_port": node["coordinator_port"],
        "replicator_ip": node["target_ip"],
        "replicator_port": node["replicator_port"]
    }
    for node in nodes_data
]

os.makedirs("/configs", exist_ok=True)

for idx, node in enumerate(nodes_data):
    config = {
        "cluster": cluster_section,
        "cache_server_ip": node["target_ip"],
        "cache_port": node["target_port"],
        "coordinator_ip": node["coordinator_ip"],
        "coordinator_port": node["coordinator_port"],
        "partitions": 1024,
        "num_threads": 4,
        "cpu_pin_offset": node["cpu_pin_offset"],
        "hello_delay": 10,
        "force_start_delay": 20,
        "cache_stat_port": node["stat_port"],
        "heartbeat": 5,
        "replica": {
            "port": node["replicator_port"],
            "ip": node["target_ip"],
            "num_rep_threads": 2,
            "cpu_pin_offset": node["rep_cpu_pin_offset"]
        }
    }

    with open(f"/configs/config-cluster-{idx}.yaml", "w") as f:
        yaml.dump(config, f, default_flow_style=False, sort_keys=False)

prometheus_targets = [f"{node['target_ip']}:{node['stat_port']}" for node in nodes_data]

prometheus_config = {
    "global": {"scrape_interval": "5s", "evaluation_interval": "5s"},
    "scrape_configs": [
        {
            "job_name": "fastcache",
            "metrics_path": "/metrics",
            "static_configs": [{"targets": prometheus_targets}]
        }
    ]
}

with open("/configs/prometheus.yml", "w") as f:
    yaml.dump(prometheus_config, f, default_flow_style=False, sort_keys=False)

print("Конфигурация успешно сгенерирована.")