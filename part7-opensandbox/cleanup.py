#!/usr/bin/env python3
"""Delete only Part 7 sandbox workloads through the local lifecycle API."""
import json
from urllib.request import Request, urlopen

BASE = 'http://127.0.0.1:8090/v1/sandboxes'
HEADERS = {'OPEN-SANDBOX-API-KEY': 'part7-local-demo-key'}


def main():
    # Restart at page 1 after each deletion so pagination cannot skip a workload.
    while True:
        found = None
        page = 1
        while True:
            with urlopen(Request(f'{BASE}?page={page}&pageSize=100', headers=HEADERS), timeout=15) as response:
                result = json.load(response)
            for item in result['items']:
                if item.get('metadata', {}).get('tutorial') == 'governed-agent-platform-part7':
                    found = item['id']
                    break
            if found or len(result['items']) < 100:
                break
            page += 1
        if found is None:
            print('No Part 7 sandbox workloads remain.')
            return
        with urlopen(Request(f'{BASE}/{found}', headers=HEADERS, method='DELETE'), timeout=30):
            print(f'Deleted Part 7 sandbox {found}')


if __name__ == '__main__':
    main()
