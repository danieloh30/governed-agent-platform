"""Fixed script for synthetic settlement reconciliation inside OpenSandbox."""
import csv
import json
from pathlib import Path
import socket
import sys
import time


def reconcile():
    root = Path(__file__).parent
    def load(name):
        with (root / name).open() as stream:
            return {row['payment_id']: int(row['amount_cents']) for row in csv.DictReader(stream)}
    ledger, settlements = load('ledger.csv'), load('settlements.csv')
    differences = []
    for payment in sorted(ledger.keys() | settlements.keys()):
        expected, actual = ledger.get(payment), settlements.get(payment)
        if expected != actual:
            differences.append({'paymentId': payment, 'expectedCents': expected,
                                'settledCents': actual,
                                'reason': 'UNEXPECTED_SETTLEMENT' if expected is None else
                                          'MISSING_SETTLEMENT' if actual is None else 'AMOUNT_MISMATCH'})
    return {'ledgerTotalCents': sum(ledger.values()),
            'settledTotalCents': sum(settlements.values()),
            'deltaCents': sum(settlements.values()) - sum(ledger.values()),
            'discrepancies': differences, 'syntheticData': True}


def egress_check():
    results = []
    for host in ['example.com', '1.1.1.1']:
        try:
            with socket.create_connection((host, 443), timeout=3):
                results.append({'target': host, 'connected': True})
        except OSError as error:
            results.append({'target': host, 'connected': False, 'error': str(error)})
    if any(result['connected'] for result in results):
        print(json.dumps({'probes': results, 'allBlocked': False}), flush=True)
        raise SystemExit(3)
    return {'probes': results, 'allBlocked': True}


if __name__ == '__main__':
    scenario = sys.argv[1]
    if scenario == 'reconcile':
        print(json.dumps(reconcile()), flush=True)
    elif scenario == 'egress-check':
        print(json.dumps(egress_check()), flush=True)
    elif scenario == 'timeout':
        print('Starting a 30-second sleep with a 2-second execution deadline', flush=True)
        time.sleep(30)
        print('UNEXPECTED: deadline was not enforced', flush=True)
    elif scenario == 'script-error':
        print('Deliberate synthetic reconciliation failure', file=sys.stderr, flush=True)
        raise SystemExit(7)
    else:
        raise SystemExit('Unknown scenario')
