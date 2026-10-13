"""Sightings per species in each province, and per month in all of China (iNaturalist species_counts), for the location/season prior. Resumable: what is in geo.json / months.json is kept."""
import json, time, urllib.request
D = '/tmp/bc-data'
UA = {"User-Agent": "MinisResearch/0.1 (species prior for on-device ID; nazhenhuiyi@gmail.com)"}
def get(url):
    for a in range(8):
        try: return json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=40))
        except Exception as e: print('retry', e, flush=True); time.sleep(10 + 10 * a)
    raise RuntimeError('gave up ' + url)
def counts(params, max_pages=40):
    out = {}
    for page in range(1, max_pages + 1):
        rs = get(f"https://api.inaturalist.org/v1/observations/species_counts?verifiable=any&per_page=500&page={page}&{params}").get('results', [])
        for x in rs: out[x['taxon']['name']] = x['count']
        time.sleep(1.5)
        if len(rs) < 500: break
    return out
def load(name):
    try: return json.load(open(f'{D}/{name}'))
    except FileNotFoundError: return {}
geo = load('geo.json')
for pid, en, zh in json.load(open(f'{D}/provinces.json')):
    if str(pid) in geo: continue
    geo[str(pid)] = counts(f"place_id={pid}"); print('province', en, len(geo[str(pid)]), flush=True)
    json.dump(geo, open(f'{D}/geo.json', 'w'))
months = load('months.json')
for m in range(1, 13):
    if str(m) in months: continue
    months[str(m)] = counts(f"place_id=6903&month={m}"); print('month', m, len(months[str(m)]), flush=True)
    json.dump(months, open(f'{D}/months.json', 'w'))
print('ALLDONE', flush=True)
