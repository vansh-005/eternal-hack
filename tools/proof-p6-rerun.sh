#!/usr/bin/env bash
# Waits for the main proof driver to finish, then reruns P6 (ceiling 250) with trace sampling 0.1.
cd "$(dirname "$0")/.."
export PATH="$PATH:/c/Users/vansh/AppData/Local/Microsoft/WinGet/Packages/ezwinports.make_Microsoft.Winget.Source_8wekyb3d8bbwe/bin"
export MSYS_NO_PATHCONV=1
PROOF_DIR=${PROOF_DIR:-$PWD/.proof-state}
until grep -q "ALL DONE" "$PROOF_DIR/progress.txt"; do sleep 10; done
DC="docker compose -f infra/docker-compose.yml"
TRACING_SAMPLE=0.1 $DC up -d nocook-1 nocook-2 nocook-3 mock-oms > "$PROOF_DIR/sample2.log" 2>&1
sleep 60
echo "$(date +%H:%M:%S) START P6-rerun" >> "$PROOF_DIR/progress.txt"
node tools/demo-reset.mjs > "$PROOF_DIR/P6r.reset.log" 2>&1
node tools/run.mjs --profile P6 --note "P6 knee ramp, ceiling 250 cancels/s" > "$PROOF_DIR/P6r.log" 2>&1
echo "$(date +%H:%M:%S) END   P6-rerun rc=$? $(grep -E 'VERDICT' "$PROOF_DIR/P6r.log" | tail -1)" >> "$PROOF_DIR/progress.txt"
TRACING_SAMPLE=1.0 $DC up -d nocook-1 nocook-2 nocook-3 mock-oms >> "$PROOF_DIR/sample2.log" 2>&1
echo "$(date +%H:%M:%S) P6 RERUN DONE" >> "$PROOF_DIR/progress.txt"
