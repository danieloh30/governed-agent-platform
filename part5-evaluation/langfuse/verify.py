"""Verify Langfuse persisted this report's item scores and actual linked tool spans."""
import argparse
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
from pathlib import Path
import time

from langfuse import Langfuse
from stack import settings


def verify(client, report):
    if len(report.get('runs', [])) != 2:
        return False
    for run in report['runs']:
        remote = client.api.experiments.list_items(
            from_start_time=datetime.now(timezone.utc)-timedelta(days=7),
            experiment_id=run['experiment_id'], fields='core,io,scores', limit=100)
        rows = {}
        for item in remote.data:
            value = json.loads(item.input) if isinstance(item.input, str) else item.input
            rows[value['case_id']] = item
        if set(rows) != {row['case_id'] for row in run['items']}:
            return False
        for row in run['items']:
            item = rows[row['case_id']]
            scores = {value.name:value.value for value in item.scores or []}
            if any(name not in scores or abs(scores[name]-value)>1e-6 for name,value in row['scores'].items()):
                return False
            observations = client.api.observations.get_many(trace_id=row['trace_id'],fields='core,basic',limit=100).data
            agent = next((obs for obs in observations if obs.name=='goose.refund-investigation'),None)
            if agent is None:
                return False
            expected = Counter('refund.'+event['operation'] for event in (row.get('output',{}).get('evidence') or {}).get('events',[]))
            observed = Counter(obs.name for obs in observations if obs.parent_observation_id==agent.id)
            if expected-observed:
                return False
    return True


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--report',type=Path,default=Path(__file__).resolve().parent.parent/'target/langfuse-comparison.json')
    parser.add_argument('--timeout',type=int,default=60)
    args=parser.parse_args()
    report=json.loads(args.report.read_text())
    config=settings()
    client=Langfuse(public_key=config['LANGFUSE_PUBLIC_KEY'],secret_key=config['LANGFUSE_SECRET_KEY'],base_url=config['LANGFUSE_BASE_URL'])
    deadline=time.monotonic()+args.timeout
    try:
        while not verify(client,report):
            if time.monotonic()>=deadline:
                raise RuntimeError('Persisted experiment scores or linked tool spans are incomplete')
            time.sleep(2)
        print('PASS both experiment runs, per-case scores, and real tool spans match local evidence')
    finally:
        client.shutdown()


if __name__=='__main__':main()
