#!/bin/sh
CONFIG_FILE="/configs/config-cluster-${NODE_ID}.yaml"

echo "Ожидание полной конфигурации $CONFIG_FILE..."
while [ ! -f "$CONFIG_FILE" ]; do
  sleep 1
done

echo "Запуск fastcache-node-${NODE_ID}..."
hostname -I
exec /app/server-fastcache -config-file "$CONFIG_FILE"