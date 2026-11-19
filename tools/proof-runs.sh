#!/usr/bin/env bash
# Sequential proof-run driver (Q-09). Each run starts from a demo-reset stack.
# Usage: bash tools/proof-runs.sh  (logs to $PROOF_DIR/*.log, progress in $PROOF_DIR/progress.txt)
cd "$(dirname "$0")/.."
export PATH="$PATH:/c/Users/vansh/AppData/Local/Microsoft/WinGet/Packages/ezwinports.make_Microsoft.Winget.Source_8wekyb3d8bbwe/bin"
export MSYS_NO_PATHCONV=1
PROOF_DIR=${PROOF_DIR:-$PWD/.proof-state}
mkdir -p $PROOF_DIR
step() { # name, command...
  name=$1; shift
  [ -f $PROOF_DIR/done.$name ] && { echo "skip $name (done)" >> $PROOF_DIR/progress.txt; return; }
  echo "$(date +%H:%M:%S) START $name" >> $PROOF_DIR/progress.txt
  node tools/demo-reset.mjs > $PROOF_DIR/$name.reset.log 2>&1
  "$@" > $PROOF_DIR/$name.log 2>&1
  rc=$?; [ $rc -eq 0 ] && touch $PROOF_DIR/done.$name
  echo "$(date +%H:%M:%S) END   $name rc=$rc  $(grep -E 'verdict|VERDICT' $PROOF_DIR/$name.log | tail -1)" >> $PROOF_DIR/progress.txt
}
DC="docker compose -f infra/docker-compose.yml"
step P2 node tools/run.mjs --profile P2 --note "proof run P2 (NYE record)"
for F in F2 F3 F4 F5 F6 F7; do
  step P3-$F node tools/run.mjs --profile P3 --fault $F --note "fault run $F"
done
# capacity runs: lower trace sampling per LOAD_AND_CHAOS 2
TRACING_SAMPLE=0.1 $DC up -d nocook-1 nocook-2 nocook-3 mock-oms > $PROOF_DIR/sample.log 2>&1
sleep 45
step P4-1rep node tools/run.mjs --profile P4 --replicas 1 --note "P4 one replica (F1 baseline)"
step P4 node tools/run.mjs --profile P4 --note "P4 three replicas"
step P5 node tools/run.mjs --profile P5 --note "P5 burst"
step P6 node tools/run.mjs --profile P6 --note "P6 knee ramp"
TRACING_SAMPLE=1.0 $DC up -d nocook-1 nocook-2 nocook-3 mock-oms >> $PROOF_DIR/sample.log 2>&1
sleep 45
step P3-F7 node tools/run.mjs --profile P3 --fault F7 --note "fault run F7 (race x500)"
echo "$(date +%H:%M:%S) ALL DONE" >> $PROOF_DIR/progress.txt
