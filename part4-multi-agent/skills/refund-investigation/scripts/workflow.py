#!/usr/bin/env python3
"""Submit or inspect a Part 4 A2A task. Does not expose admin approval actions."""

import argparse
import json
import sys
import uuid
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--url', default='http://localhost:8082',
                        help='Local Part 4 server URL (default: http://localhost:8082)')
    commands = parser.add_subparsers(dest='action', required=True)
    commands.add_parser('submit', help='Submit one workflow command').add_argument('command')
    commands.add_parser('status', help='Inspect an existing task').add_argument('task_id')
    args = parser.parse_args()
    address = urlsplit(args.url)
    if (address.scheme != 'http' or address.hostname not in ('localhost', '127.0.0.1', '::1')
            or address.username or address.password or address.query or address.fragment
            or address.path not in ('', '/')):
        parser.error('--url must be an HTTP localhost server URL without a path or credentials')

    if args.action == 'submit':
        method = 'SendMessage'
        params = {
            'message': {'messageId': str(uuid.uuid4()), 'role': 'ROLE_USER',
                        'parts': [{'text': args.command}]},
            'configuration': {'returnImmediately': False},
        }
    else:
        method, params = 'GetTask', {'id': args.task_id}
    payload = {'jsonrpc': '2.0', 'id': str(uuid.uuid4()), 'method': method, 'params': params}
    request = Request(args.url.rstrip('/') + '/', data=json.dumps(payload).encode(),
                      headers={'Content-Type': 'application/json', 'A2A-Version': '1.0'})
    try:
        with urlopen(request, timeout=30) as response:
            result = json.load(response)
        if 'error' in result:
            raise ValueError(f"A2A error: {json.dumps(result['error'])}")
        task = result.get('result', {})
        if method == 'SendMessage':
            task = task.get('task', {})
        if not task.get('id') or not task.get('status', {}).get('state'):
            raise ValueError('Server response did not contain an A2A task ID and state')
        print(json.dumps(task, indent=2, ensure_ascii=False))
    except (HTTPError, URLError, TimeoutError, ValueError) as error:
        print(f'Workflow request failed: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
