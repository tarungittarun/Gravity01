#!/usr/bin/env python3
"""Bounded WIMT *web cache* position trial, never the Google Android app's cell feed.

One selected train/run, two public appVersion parameters, and one later repeat. This
identifies whether the root distance/curStn is an observation or just a changing
response timestamp. It prints only train-status fields, never credentials/device data.
No application integration is inferred from an HTTP 200 or a fresh root clock.
"""
import datetime as dt
import json
import time
import urllib.parse
import urllib.request

HOST = 'https://whereismytrain.in/cache/live_status?'
VERSIONS = ('7.1.5.633808517', '7.1.5.974952539')
IST = dt.timezone(dt.timedelta(hours=5, minutes=30))


def request(version):
    params = urllib.parse.urlencode(dict(date='14-10-2026', train_no='16227',
        **{'from': 'MYS', 'to': 'TLGP'}, from_day='1', lang='en',
        appVersion=version, sb_version='0'))
    req = urllib.request.Request(HOST + params, headers={
        'Accept': 'application/json', 'User-Agent': 'OpenTrack India source research/1.0'})
    start = time.monotonic()
    with urllib.request.urlopen(req, timeout=7) as response:
        if response.status != 200: return f'HTTP {response.status}'
        data = json.loads(response.read(90_000))
    if not isinstance(data, dict): return f'non-object ({type(data).__name__})'
    now = dt.datetime.now(IST)
    stamp = data.get('lastUpdateIsoDate')
    try:
        age = round((now - dt.datetime.fromisoformat(stamp)).total_seconds() / 60, 1)
    except (TypeError, ValueError):
        age = '?'
    rows = data.get('days_schedule') or []
    if not isinstance(rows, list): rows = []
    cur = str(data.get('curStn', ''))
    def compact(row):
        return {k: row.get(k) for k in ('station_code', 'source', 'departed',
            'updated_at', 'actual_arrival_time', 'actual_departure_time',
            'actual_arrival_tm', 'actual_departure_tm') if k in row}
    at = [i for i, r in enumerate(rows) if isinstance(r, dict) and r.get('station_code') == cur]
    near = rows[max(0, at[0]-1):at[0]+2] if at else []
    # A root-level value called distance is not known to be observed distance.
    return (f'{version}: {len(rows)} rows, {time.monotonic()-start:.1f}s; '
        f'run={data.get("start_date")} route={data.get("source_station")}→{data.get("destination_station")}; '
        f'root stamp={stamp} (age {age}m), curStn={cur!r}, distance={data.get("distance")!r}, '
        f'eta={str(data.get("eta"))[:150]!r}, next={str(data.get("pitstop_next_to_curstn"))[:150]!r}; '
        f'nearby rows={[compact(r) for r in near if isinstance(r, dict)]}')[:1500]

if __name__ == '__main__':
    print('16227 / 14 Oct 2026. Root response time != position event time. WIMT web != phone app.', flush=True)
    for version in VERSIONS:
        try: print('  ' + request(version), flush=True)
        except Exception as exc: print(f'  {version}: {type(exc).__name__} {str(exc)[:100]}', flush=True)
    time.sleep(18)  # one repeat, not a polling service
    try: print('  18s later ' + request(VERSIONS[-1]), flush=True)
    except Exception as exc: print(f'  repeat: {type(exc).__name__} {str(exc)[:100]}', flush=True)
