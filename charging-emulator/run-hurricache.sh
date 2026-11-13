#!/bin/bash
# Charging emulator run against HurriCache.
# Provisioning of balance:* keys runs automatically (--provision);
# after the run the keys are removed from the cluster (disable: --keep-data).
# Usage:   ./run-hurricache.sh [extra emulator arguments]
# Example: ./run-hurricache.sh --threads 32 --parents-percent 50
cd "$(dirname "$0")"
mvn -q compile exec:java -Dexec.mainClass=com.hurricache.emulator.ChargingEmulator -Dexec.args="--mode hurricache --provision --accounts 100000 --parents-percent 20 --threads 8 --duration-sec 60 $*"
