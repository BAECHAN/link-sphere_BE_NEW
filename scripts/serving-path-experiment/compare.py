#!/usr/bin/env python3
"""같은 요청을 Tomcat(HTTP)과 RIE(Function URL 이벤트)에 보내 응답을 비교한다. 표준 라이브러리만 쓴다.

  compare.py run --target mockmvc --kind rie --port 19001 --container serving-mockmvc --phase main --out raw/mockmvc.json
  compare.py report --dir raw
  compare.py report --dir raw --gate accepted-differences.json   # PR 검사(run.sh GATE=1)

run.sh가 컨테이너를 띄우고 이 스크립트를 부른다. 이벤트 형식은 AWS 문서를 따른다
(https://docs.aws.amazon.com/lambda/latest/dg/urls-invocation.html). 응답도 그 문서의 필드(statusCode·headers·cookies·body·
isBase64Encoded)만 읽는다. multiValueHeaders처럼 문서에 없는 필드로만 나간 값은 사용자에게 안 간다고 친다.
"""
import argparse
import base64
import http.client
import json
import pathlib
import re
import subprocess
import sys
import time
import urllib.parse

HERE = pathlib.Path(__file__).resolve().parent
RIE_PATH = '/2015-03-31/functions/function/invocations'
LOGIN_BODY = '{"email":"experiment-user@example.invalid","password":"experiment-password"}'
SEARCH_TIMEOUT_SECONDS = 170

# Managed-AllViewerExceptHostHeader가 넘기는 헤더와 같은 모양. Tomcat 기준 요청에도 같이 보낸다
COMMON_HEADERS = {
    'host': 'example.lambda-url.ap-northeast-1.on.aws',
    'accept': 'application/json',
    'user-agent': 'serving-path-experiment',
    'x-forwarded-proto': 'https',
    'x-forwarded-port': '443',
    'x-forwarded-for': '203.0.113.10, 198.51.100.1',
    'cloudfront-viewer-address': '203.0.113.10:4321',
    'via': '2.0 experiment.cloudfront.net (CloudFront)',
    'x-amz-cf-id': 'experiment-dummy',
    'x-amzn-trace-id': 'Root=1-00000000-000000000000000000000000',
}

# 전송 계층이나 앞단이 다시 만드는 값이라 비교에서 뺀다
IGNORED_HEADERS = {'date', 'connection', 'keep-alive', 'transfer-encoding', 'content-length'}


def normalize_text(text):
    text = re.sub(r'\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?(Z|[+-]\d{2}:\d{2})?', '<time>', text)
    text = re.sub(r'"accessToken":"[^"]*"', '"accessToken":"<token>"', text)
    return text


def normalize_body(text):
    text = normalize_text(text)

    try:
        data = json.loads(text)
    except ValueError:
        return text

    # 검증 오류를 잇는 순서는 경로와 상관없이 실행마다 달라진다
    if isinstance(data, dict) and data.get('code') == 'INVALID_INPUT' and isinstance(data.get('message'), str):
        data['message'] = '; '.join(sorted(data['message'].split('; ')))

    return json.dumps(data, ensure_ascii=False, sort_keys=True)


def normalize_header(key, value):
    # 남은 시간은 요청 시각에 따라 달라진다
    if key == 'retry-after':
        return '<seconds>'

    # 후보 B는 ;charset을 ; charset으로 쓴다. 뜻이 같아 비교에서 뺀다(결과 문서에 적어 둔다)
    if key == 'content-type':
        return re.sub(r';\s+', ';', value)

    return value


def normalize_cookie(cookie):
    cookie = re.sub(r'__Host-refreshToken=[^;]+', '__Host-refreshToken=<token>', cookie)
    return re.sub(r'Expires=[^;]+', 'Expires=<date>', cookie)


def canonical(status, headers, cookies, body, function_error=None):
    kept = {key.lower(): normalize_header(key.lower(), value) for key, value in headers.items() if key.lower() not in IGNORED_HEADERS}
    return {
        'status': status,
        'headers': dict(sorted(kept.items())),
        'cookies': sorted(normalize_cookie(cookie) for cookie in cookies),
        'body': normalize_body(body),
        'functionError': function_error,
    }


def expand(value, variables):
    if isinstance(value, str):
        for key, replacement in variables.items():
            value = value.replace('{{' + key + '}}', str(replacement))

        return value

    if isinstance(value, list):
        return [expand(item, variables) for item in value]

    if isinstance(value, dict):
        return {key: expand(item, variables) for key, item in value.items()}

    return value


def header_bytes(method, path, headers):
    return len(f'{method} {path} HTTP/1.1\r\n') + sum(len(key) + 2 + len(value) + 2 for key, value in headers.items())


def request_parts(spec, variables):
    headers = dict(COMMON_HEADERS)
    headers.update(expand(spec.get('headers', {}), variables))

    # 공통 헤더 중 일부가 없는 요청. CloudFront는 X-Forwarded-Proto를 지우고 오리진에 넘긴다(AWS CloudFront 문서
    # "Request and response behavior for custom origins"의 요청 헤더 표)
    for key in spec.get('dropHeaders', []):
        headers.pop(key, None)
    cookies = expand(spec.get('cookies', []), variables)
    method = spec.get('method', 'GET')
    path = expand(spec.get('path', '/'), variables)
    body = expand(spec.get('body'), variables)

    if 'bodyTotalBytes' in spec:
        pad = 'x' * (spec['bodyTotalBytes'] - len(body.replace('{{pad}}', '')))
        body = body.replace('{{pad}}', pad)

    if 'headerTotalBytes' in spec:
        used = header_bytes(method, path, headers) + len('x-padding: \r\n')
        headers['x-padding'] = 'x' * max(spec['headerTotalBytes'] - used, 0)

    return method, path, headers, cookies, body


def function_url_event(method, path, headers, cookies, body, force_base64):
    raw_path, _, raw_query = path.partition('?')
    params = {}

    for key, value in urllib.parse.parse_qsl(raw_query, keep_blank_values=True, errors='replace'):
        params.setdefault(key, []).append(value)

    event = {
        'version': '2.0',
        'routeKey': '$default',
        'rawPath': raw_path,
        'rawQueryString': raw_query,
        'headers': headers,
        'requestContext': {
            'accountId': '123456789012',
            'apiId': 'example',
            'authentication': None,
            'authorizer': {
                'iam': {
                    'accessKey': 'EXPERIMENTDUMMY',
                    'accountId': '111122223333',
                    'callerId': 'EXPERIMENTDUMMY',
                    'cognitoIdentity': None,
                    'principalOrgId': None,
                    'userArn': 'arn:aws:iam::111122223333:user/experiment-dummy',
                    'userId': 'EXPERIMENTDUMMY',
                },
            },
            'domainName': 'example.lambda-url.ap-northeast-1.on.aws',
            'domainPrefix': 'example',
            'http': {
                'method': method,
                'path': raw_path,
                'protocol': 'HTTP/1.1',
                'sourceIp': '198.51.100.1',
                'userAgent': headers.get('user-agent', ''),
            },
            'requestId': 'experiment-request',
            'routeKey': '$default',
            'stage': '$default',
            'time': '07/Nov/2026:00:00:00 +0000',
            'timeEpoch': 1793923200000,
        },
        'pathParameters': None,
        'isBase64Encoded': False,
        'stageVariables': None,
    }

    if cookies:
        event['cookies'] = cookies

    if params:
        event['queryStringParameters'] = {key: ','.join(values) for key, values in params.items()}

    if body is not None:
        if force_base64:
            event['body'] = base64.b64encode(body.encode()).decode()
            event['isBase64Encoded'] = True
        else:
            event['body'] = body

    return event


class Target:
    def __init__(self, name, kind, port, container):
        self.name = name
        self.kind = kind
        self.port = port
        self.container = container

    def invoke_event(self, event):
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=SEARCH_TIMEOUT_SECONDS)
        connection.request('POST', RIE_PATH, body=json.dumps(event, ensure_ascii=False).encode())
        response = connection.getresponse()
        raw = response.read().decode('utf-8', 'replace')
        function_error = response.getheader('X-Amz-Function-Error')
        connection.close()

        try:
            payload = json.loads(raw)
        except ValueError:
            return canonical(200, {}, [], raw)

        if function_error or (isinstance(payload, dict) and 'errorType' in payload and 'statusCode' not in payload):
            error_type = payload.get('errorType', 'unknown') if isinstance(payload, dict) else 'unknown'
            # Function URL은 함수 실패를 502로 돌려준다
            return canonical(502, {}, [], '', function_error=error_type)

        if not (isinstance(payload, dict) and 'statusCode' in payload):
            return canonical(200, {}, [], raw)

        body = payload.get('body') or ''

        if payload.get('isBase64Encoded'):
            body = base64.b64decode(body).decode('utf-8', 'replace')

        return canonical(payload['statusCode'], payload.get('headers') or {}, payload.get('cookies') or [], body)

    def send_http(self, method, path, headers, cookies, body):
        connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=SEARCH_TIMEOUT_SECONDS)
        connection.putrequest(method, path, skip_host=True, skip_accept_encoding=True)
        encoded = body.encode() if body is not None else None

        for key, value in headers.items():
            connection.putheader(key, value)

        if cookies:
            connection.putheader('cookie', '; '.join(cookies))

        if encoded is not None:
            connection.putheader('content-length', str(len(encoded)))

        connection.endheaders(encoded)
        response = connection.getresponse()
        raw = response.read().decode('utf-8', 'replace')
        merged = {}
        set_cookies = []

        for key, value in response.getheaders():
            if key.lower() == 'set-cookie':
                set_cookies.append(value)
            else:
                merged.setdefault(key, []).append(value)

        connection.close()
        return canonical(response.status, {key: ', '.join(values) for key, values in merged.items()}, set_cookies, raw)

    def send(self, spec, variables):
        if 'event' in spec:
            return self.invoke_event(spec['event'])

        method, path, headers, cookies, body = request_parts(spec, variables)

        if self.kind == 'http':
            return self.send_http(method, path, headers, cookies, body)

        return self.invoke_event(function_url_event(method, path, headers, cookies, body, spec.get('bodyBase64', False)))

    def login(self):
        spec = {'method': 'POST', 'path': '/api/auth/login', 'headers': {'content-type': 'application/json'}, 'body': LOGIN_BODY}
        method, path, headers, cookies, body = request_parts(spec, {})

        # 정규화 전 토큰이 필요해 canonical을 거치지 않고 읽는다
        if self.kind == 'http':
            connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=SEARCH_TIMEOUT_SECONDS)
            connection.request(method, path, body=body.encode(), headers=headers)
            response = connection.getresponse()
            data = json.loads(response.read().decode())
            set_cookies = [value for key, value in response.getheaders() if key.lower() == 'set-cookie']
            connection.close()
        else:
            connection = http.client.HTTPConnection('127.0.0.1', self.port, timeout=SEARCH_TIMEOUT_SECONDS)
            event = function_url_event(method, path, headers, cookies, body, False)
            connection.request('POST', RIE_PATH, body=json.dumps(event).encode())
            payload = json.loads(connection.getresponse().read().decode())
            connection.close()
            data = json.loads(payload['body'])
            set_cookies = payload.get('cookies') or []

        refresh = next(re.match(r'__Host-refreshToken=([^;]+)', cookie).group(1) for cookie in set_cookies if cookie.startswith('__Host-refreshToken='))
        return {'accessToken': data['data']['accessToken'], 'refreshToken': refresh}

    def logs(self):
        return subprocess.run(['docker', 'logs', self.container], capture_output=True, text=True, errors='replace').stdout

    def wait_ready(self, seconds=180):
        spec = {'method': 'GET', 'path': '/api/post?size=1'}
        deadline = time.time() + seconds

        while time.time() < deadline:
            try:
                if self.send(spec, {})['status'] == 200:
                    return True
            except (OSError, http.client.HTTPException):
                pass

            time.sleep(1)

        return False

    def wait_listening(self, seconds=180):
        """응답 내용은 보지 않는다. Tomcat은 기동이 끝나기 전이면 연결을 끊는다."""
        spec = {'method': 'GET', 'path': '/api/actuator/health'}
        deadline = time.time() + seconds

        while time.time() < deadline:
            try:
                self.send(spec, {})
                return
            except (OSError, http.client.HTTPException):
                time.sleep(1)

    def measure_recovery(self, seconds=60):
        """DB가 돌아온 직후의 첫 응답과 200까지 걸린 시간을 잰다. 연결 실패 뒤 복구가 대상마다 다른지 본다."""
        spec = {'method': 'GET', 'path': '/api/post?size=1'}
        started = time.time()
        first = None
        attempts = 0

        while time.time() - started < seconds:
            attempts += 1
            status = self.send(spec, {})['status']
            first = first if first is not None else status

            if status == 200:
                return {'firstStatus': first, 'attempts': attempts, 'secondsUntilOk': round(time.time() - started, 1)}

            time.sleep(1)

        return {'firstStatus': first, 'attempts': attempts, 'secondsUntilOk': None}


def startup_meta(logs):
    """한 번 잰 참고 값이라 결론에 쓰지 않는다. RIE의 REPORT는 init 시간을 제대로 재지 않아 쓰지 않는다."""
    started = re.search(r'Started \S+ in ([\d.]+) seconds', logs)
    return {'springStartedSeconds': float(started.group(1)) if started else None}


def exception_causes(logs):
    """로그 원문은 남기지 않고 원인 예외의 클래스 이름만 모은다."""
    return sorted(set(re.findall(r'Caused by: ([\w.$]+)', logs)))


def db_query(sql):
    result = subprocess.run(
        ['docker', 'exec', 'serving-db', 'psql', '-U', 'postgres', '-d', 'exp', '-X', '-Atc', sql],
        capture_output=True,
        text=True,
    )
    return result.stdout.strip()


def docker_db(action):
    subprocess.run(['docker', action, 'serving-db'], capture_output=True, check=True)

    if action == 'start':
        for _ in range(60):
            ready = subprocess.run(['docker', 'exec', 'serving-db', 'pg_isready', '-q', '-h', '127.0.0.1', '-U', 'postgres'])

            if ready.returncode == 0:
                return

            time.sleep(1)


def check_expect(spec, response):
    """운영 요구로 정한 기대값을 본다. Tomcat과 같다고 다 맞는 것은 아니다."""
    expect = spec.get('expect')

    if not expect:
        return None

    failures = []

    if 'status' in expect and response['status'] != expect['status']:
        failures.append(f"상태 {response['status']}(기대 {expect['status']})")

    if 'bodyHas' in expect and expect['bodyHas'] not in response['body']:
        failures.append(f"본문에 {expect['bodyHas']} 없음")

    if 'cookie' in expect and not any(expect['cookie'] in cookie for cookie in response['cookies']):
        failures.append(f"cookies에 {expect['cookie']} 없음")

    for key, value in expect.get('header', {}).items():
        if value not in response['headers'].get(key, ''):
            failures.append(f'헤더 {key}에 {value} 없음')

    if expect.get('functionError') is True and not response['functionError']:
        failures.append(f"함수 실패가 아니라 {response['status']} 응답")

    return failures


def check_logs(spec, logs):
    """로그의 이메일 가림과 요청 ID 연결을 본다. 로그 원문은 결과에 남기지 않는다."""
    check = spec['logCheck']
    json_lines = [line for line in logs.splitlines() if check['marker'] in line and line.startswith('{')]
    last = json.loads(json_lines[-1]) if json_lines else {}
    starts = set(re.findall(r'START RequestId: ([0-9a-f-]+)', logs))
    request_id = last.get('awsRequestId')
    return {
        'emailMasked': check['absent'] not in logs,
        'requestIdLinked': bool(request_id and request_id in starts),
        'markerFound': bool(json_lines),
    }


def run_one(target, spec, variables):
    record = {'id': spec['id']}
    local = dict(variables)

    if spec.get('session'):
        local.update(target.login())

    for index in range(spec.get('repeatBefore', 0)):
        target.send(spec, {**local, 'i': index})

    response = target.send(spec, {**local, 'i': 'final'})
    record['response'] = response
    record['expect'] = check_expect(spec, response)

    if 'dbCheck' in spec:
        value = db_query(spec['dbCheck']['sql'])
        record['dbValue'] = value

        if 'expect' in spec['dbCheck'] and value != spec['dbCheck']['expect']:
            record.setdefault('expect', [])
            record['expect'] = (record['expect'] or []) + [f"DB 값 {value}(기대 {spec['dbCheck']['expect']})"]

    if 'logCheck' in spec:
        record['logCheck'] = check_logs(spec, target.logs())

    # 응답이 ok여도 그 서비스까지 갔다는 뜻은 아니다(후보 A는 모르는 작업에도 ok를 낸다)
    if 'logHas' in spec:
        record['expect'] = (record['expect'] or []) + ([] if spec['logHas'] in target.logs() else [f"로그에 {spec['logHas']} 없음"])

    return record


def run_hooks(target):
    """후보 A의 스냅샷 훅을 컨테이너 안에서 직접 부른다. RIE에는 SnapStart가 없어 흐름만 확인한다."""

    def curl(path, *extra):
        result = subprocess.run(
            ['docker', 'exec', target.container, 'curl', '-s', '-o', '/dev/null', '-w', '%{http_code}', '-X', 'POST', *extra, f'http://127.0.0.1:8081{path}'],
            capture_output=True,
            text=True,
        )
        return result.stdout.strip()

    def connections():
        return db_query("select count(*) from pg_stat_activity where datname='exp' and pid <> pg_backend_pid()")

    before_connections = connections()
    loopback_job = curl('/api/internal/lambda-events', '-H', 'content-type: application/json', '-d', '{"linksphereJob":"account-purge"}')
    before = curl('/api/internal/snapstart/before')
    stopped_connections = connections()
    after = curl('/api/internal/snapstart/after')
    resumed = target.send({'method': 'GET', 'path': '/api/post?size=1'}, {})['status']
    second = target.send({'method': 'GET', 'path': '/api/post?size=1'}, {})['status']
    return {
        'connectionsBefore': before_connections,
        'loopbackJobWithoutContext': loopback_job,
        'beforeCheckpoint': before,
        'connectionsAfterBefore': stopped_connections,
        'afterRestore': after,
        'firstRequestAfterRestore': resumed,
        'secondRequestAfterRestore': second,
    }


def command_run(args):
    specs = json.loads((HERE / 'requests.json').read_text())
    target = Target(args.target, args.kind, args.port, args.container)
    out = pathlib.Path(args.out)
    result = json.loads(out.read_text()) if out.exists() else {'target': args.target, 'kind': args.kind, 'records': {}, 'meta': {}}

    if args.phase == 'ready':
        ready = target.wait_ready()
        print(f'{args.target} ready={ready}', file=sys.stderr)
        sys.exit(0 if ready else 1)

    phases = {
        'main': [spec for spec in specs if 'phase' not in spec],
        'db-down': [spec for spec in specs if spec.get('phase') == 'db-down'],
        'db-up-again': [spec for spec in specs if spec.get('phase') == 'db-up-again'],
        'db-boot': [spec for spec in specs if spec.get('phase') == 'db-boot'],
        'db-boot-again': [spec for spec in specs if spec.get('phase') == 'db-boot-again'],
    }

    if args.phase == 'hooks':
        result['meta']['hooks'] = run_hooks(target)
        out.write_text(json.dumps(result, ensure_ascii=False, indent=1))
        return

    variables = {}

    if args.phase == 'db-down':
        variables = target.login()
        docker_db('stop')

    if args.phase == 'db-boot':
        target.wait_listening()

    if args.phase == 'db-boot-again':
        result['meta']['recoveryAfterBootWithoutDb'] = target.measure_recovery()
        result['meta']['causesAfterBootWithoutDb'] = exception_causes(target.logs())

    for spec in phases[args.phase]:
        if spec.get('lambdaOnly') and target.kind == 'http':
            continue

        try:
            record = run_one(target, spec, variables)
        except Exception as error:  # noqa: BLE001 - 한 항목의 실패가 나머지 비교를 막지 않게 한다
            record = {'id': spec['id'], 'notRun': f'{type(error).__name__}: {error}'}

        result['records'][spec['id']] = record
        print(f"{args.target} {spec['id']} {record.get('response', {}).get('status', record.get('notRun'))}", file=sys.stderr)

    if args.phase == 'db-down':
        docker_db('start')
        result['meta']['recoveryAfterDbDown'] = target.measure_recovery()

    if args.phase == 'main':
        result['meta']['startup'] = startup_meta(target.logs())

    out.write_text(json.dumps(result, ensure_ascii=False, indent=1))


def differences(base, other):
    if base is None or other is None:
        return ['비교 대상 없음']

    found = []

    if base['status'] != other['status']:
        found.append(f"상태 {base['status']} → {other['status']}")

    if bool(base['functionError']) != bool(other['functionError']):
        found.append(f"함수 실패 {bool(base['functionError'])} → {bool(other['functionError'])}")
    elif base['functionError'] != other['functionError']:
        found.append(f"오류 종류 {base['functionError']} → {other['functionError']}")

    for key in sorted(set(base['headers']) | set(other['headers'])):
        if base['headers'].get(key) != other['headers'].get(key):
            found.append(f"헤더 {key}: {base['headers'].get(key)!r} → {other['headers'].get(key)!r}")

    if base['cookies'] != other['cookies']:
        found.append(f"쿠키: {base['cookies']} → {other['cookies']}")

    if base['body'] != other['body']:
        found.append(f"본문: {snippet(base['body'], other['body'])}{json_path_note(base['body'], other['body'])}")

    return found


def json_paths(left, right, path='$'):
    """다른 JSON 경로를 모은다. snippet은 첫 차이만 보여서 뒤의 차이를 놓친다."""
    if isinstance(left, dict) and isinstance(right, dict):
        found = []

        for key in sorted(set(left) | set(right)):
            if key in left and key in right:
                found += json_paths(left[key], right[key], f'{path}.{key}')
            else:
                found.append(f'{path}.{key}')

        return found

    if isinstance(left, list) and isinstance(right, list) and len(left) == len(right):
        return [item for index, pair in enumerate(zip(left, right)) for item in json_paths(*pair, f'{path}[{index}]')]

    return [] if left == right else [path]


def json_path_note(left, right):
    try:
        paths = json_paths(json.loads(left), json.loads(right))
    except ValueError:
        return ''

    return f' / 다른 JSON 경로 {len(paths)}개: {paths[:8]}'


def snippet(left, right):
    index = next((i for i, (a, b) in enumerate(zip(left, right)) if a != b), min(len(left), len(right)))
    start = max(index - 40, 0)
    return f'{left[start:index + 120]!r} → {right[start:index + 120]!r}'


def cell(record, base_record, spec):
    if record is None:
        return '해당 없음'

    if 'notRun' in record:
        return '미실행'

    response = record['response']
    expect_failed = bool(record.get('expect'))
    mark = ' ✗' if expect_failed else (' ✓' if record.get('expect') is not None else '')

    if base_record is None or 'response' not in base_record:
        label = '함수 실패' if response['functionError'] else str(response['status'])
        return label + mark

    found = differences(base_record['response'], response)

    if not found:
        return '같음' + mark

    if response['functionError']:
        return '함수 실패' + mark

    kinds = []

    if base_record['response']['status'] != response['status']:
        kinds.append(f"{base_record['response']['status']}→{response['status']}")

    headers = [item.split(' ')[1].rstrip(':') for item in found if item.startswith('헤더')]

    if headers:
        kinds.append(f"헤더({', '.join(headers)})" if len(headers) <= 3 else f'헤더 {len(headers)}개')

    if any(item.startswith('쿠키') for item in found):
        kinds.append('쿠키')

    if any(item.startswith('본문') for item in found):
        kinds.append('본문')

    return ' '.join(kinds) + mark


def recovery_cell(recovery):
    if not recovery:
        return '미실행'

    return f"첫 응답 {recovery['firstStatus']}, {recovery['attempts']}번째 요청에 200"


def command_report(args):
    specs = json.loads((HERE / 'requests.json').read_text())
    directory = pathlib.Path(args.dir)
    runs = {path.stem: json.loads(path.read_text()) for path in directory.glob('*.json')}
    names = [name for name in ('tomcat', 'mockmvc', 'candidate-a', 'candidate-b', 'head') if name in runs]
    out = []
    groups = []

    for spec in specs:
        if spec['group'] not in groups:
            groups.append(spec['group'])

    out.append('표 읽는 법: 기준은 Tomcat 응답이고, Lambda 전용 묶음은 현재 MockMvc가 기준이다. ✓·✗는 운영 요구로 정한 기대값의 통과·실패다.\n')

    for group in groups:
        out.append(f'### {group}\n')
        out.append('| 항목 | ' + ' | '.join(names) + ' | 도달 여부 |')
        out.append('| --- | ' + ' | '.join('---' for _ in names) + ' | --- |')

        for spec in (spec for spec in specs if spec['group'] == group):
            baseline = 'mockmvc' if spec.get('lambdaOnly') else 'tomcat'
            base_record = runs.get(baseline, {}).get('records', {}).get(spec['id'])
            cells = []

            for name in names:
                record = runs[name]['records'].get(spec['id'])

                if name == baseline:
                    if record is None:
                        cells.append('해당 없음')
                    elif 'notRun' in record:
                        cells.append('미실행')
                    else:
                        response = record['response']
                        mark = ' ✗' if record.get('expect') else (' ✓' if record.get('expect') is not None else '')
                        cells.append(('함수 실패' if response['functionError'] else f"{response['status']}") + mark)
                else:
                    cells.append(cell(record, base_record, spec))

            title = spec['title'].replace('|', '\\|')
            reach = spec.get('reach', '도달').replace('|', '\\|')
            out.append(f"| `{spec['id']}` {title} | " + ' | '.join(cells) + f' | {reach} |')

        out.append('')

    out.append('### 차이 상세\n')

    for spec in specs:
        baseline = 'mockmvc' if spec.get('lambdaOnly') else 'tomcat'
        base_record = runs.get(baseline, {}).get('records', {}).get(spec['id'])

        for name in names:
            record = runs[name]['records'].get(spec['id'])

            if name == baseline or record is None:
                continue

            if 'notRun' in record:
                out.append(f"- `{spec['id']}` {name}: 미실행({record['notRun']})")
                continue

            if base_record is None or 'response' not in base_record:
                continue

            for item in differences(base_record['response'], record['response']):
                out.append(f"- `{spec['id']}` {name}: {item}".replace('\n', ' '))

    out.append('')
    out.append('### 기대값 실패\n')

    for spec in specs:
        for name in names:
            record = runs[name]['records'].get(spec['id'])

            if record and record.get('expect'):
                out.append(f"- `{spec['id']}` {name}: {'; '.join(record['expect'])}")

    out.append('')
    out.append('### 기대값의 근거\n')

    for spec in specs:
        if 'requirement' in spec:
            out.append(f"- `{spec['id']}`: {spec['requirement']}")

    out.append('')
    out.append('### 로그·DB·내부 작업 확인\n')

    for spec in specs:
        for name in names:
            record = runs[name]['records'].get(spec['id'])

            if record and 'logCheck' in record:
                out.append(f"- `{spec['id']}` {name}: {record['logCheck']}")

            if record and 'dbValue' in record:
                out.append(f"- `{spec['id']}` {name}: DB 값 {record['dbValue']!r}")

    out.append('')
    out.append('### DB가 돌아온 직후의 첫 응답\n')
    out.append('| 대상 | DB 중단 뒤 | DB 없이 기동한 뒤 |')
    out.append('| --- | --- | --- |')

    for name in names:
        meta = runs[name].get('meta', {})
        cells = [recovery_cell(meta.get(key)) for key in ('recoveryAfterDbDown', 'recoveryAfterBootWithoutDb')]
        out.append(f"| {name} | {cells[0]} | {cells[1]} |")

    out.append('')
    out.append('### 참고 값(결론에 쓰지 않음)\n')

    for name, run in runs.items():
        out.append(f"- {name}: {run.get('meta', {})}")

    print('\n'.join(out))

    if args.gate:
        accepted = json.loads(pathlib.Path(args.gate).read_text())['accepted']
        failures = gate_failures(specs, runs, accepted)
        print('\n## PR 검사\n')

        if failures:
            print('\n'.join(failures))
            sys.exit(1)

        print(f'통과: 기대값 실패 0건, Tomcat과의 차이가 허용 목록 {len(accepted)}개와 같다.')


def gate_failures(specs, runs, accepted):
    """head가 기대값을 지키고, Tomcat과의 차이가 허용 목록과 글자까지 같아야 통과한다.

    목록에 있는데 차이가 사라져도 실패다. 동작을 일부러 바꿨으면 그 PR에서 목록을 같이 고쳐 리뷰에 드러나게 한다.
    Lambda 전용 항목은 Tomcat이 받지 않으므로 기대값과 로그 확인만 본다.
    """
    head = runs.get('head', {}).get('records', {})
    tomcat = runs.get('tomcat', {}).get('records', {})
    ids = {spec['id'] for spec in specs}
    lambda_only = {spec['id'] for spec in specs if spec.get('lambdaOnly')}
    failures = [f'- `{key}`: 허용 목록에만 있고 요청 목록에 없다' for key in sorted(set(accepted) - ids)]
    # Lambda 전용 항목은 아래에서 Tomcat 비교를 건너뛰므로, 목록에 잘못 적으면 조용히 무시된다
    failures += [f'- `{key}`: Lambda 전용 요청이라 Tomcat과의 차이 목록에 둘 수 없다' for key in sorted(set(accepted) & lambda_only)]

    for spec in specs:
        record = head.get(spec['id'])

        if record is None or 'notRun' in record:
            failures.append(f"- `{spec['id']}`: 미실행({(record or {}).get('notRun', '결과 없음')})")
            continue

        if record.get('expect'):
            failures.append(f"- `{spec['id']}`: 기대값 실패 {'; '.join(record['expect'])}")

        if record.get('logCheck') and not all(record['logCheck'].values()):
            failures.append(f"- `{spec['id']}`: 로그 확인 실패 {record['logCheck']}")

        if spec.get('lambdaOnly'):
            continue

        base = tomcat.get(spec['id'])

        if base is None or 'response' not in base:
            failures.append(f"- `{spec['id']}`: Tomcat 결과 없음")
            continue

        found = differences(base['response'], record['response'])
        expected = accepted.get(spec['id'], {}).get('differences', [])

        if found != expected:
            failures.append(f"- `{spec['id']}`: Tomcat과의 차이가 허용 목록과 다르다. 지금 차이: {json.dumps(found, ensure_ascii=False)}")

    return failures


def main():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest='command', required=True)
    run = commands.add_parser('run')
    run.add_argument('--target', required=True)
    run.add_argument('--kind', choices=['http', 'rie'], required=True)
    run.add_argument('--port', type=int, required=True)
    run.add_argument('--container', required=True)
    run.add_argument('--phase', required=True)
    run.add_argument('--out', required=True)
    report = commands.add_parser('report')
    report.add_argument('--dir', required=True)
    report.add_argument('--gate')
    args = parser.parse_args()

    if args.command == 'run':
        command_run(args)
    else:
        command_report(args)


if __name__ == '__main__':
    main()
