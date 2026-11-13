#!/bin/bash
# Charging emulator run against Postgres.
# Provisioning of the accounts table runs automatically (--provision).
# Usage:   ./run-postgres.sh [extra emulator arguments]
# Example: ./run-postgres.sh --threads 32 --parents-percent 50
cd "$(dirname "$0")"
mvn -q compile exec:java -Dexec.mainClass=com.hurricache.emulator.ChargingEmulator -Dexec.args="--mode postgres --provision --accounts 100000 --parents-percent 20 --threads 8 --duration-sec 60 $*"
