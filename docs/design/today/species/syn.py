import json, urllib.request, urllib.parse
from concurrent.futures import ThreadPoolExecutor
UA = {"User-Agent": "MinisResearch/0.1 (nazhenhuiyi@gmail.com)"}
d = json.load(open('/tmp/bc-data/cn_inat.json'))
names = json.load(open('/tmp/bc-data/txt_emb_species.json'))
bc = set(' '.join(n[0][5:7]) for n in names)
todo = [x['n'] for x in d if x['c'] >= 3 and x['n'] not in bc]
print('unmatched', len(todo), flush=True)
def get(u):
    return json.load(urllib.request.urlopen(urllib.request.Request(u, headers=UA), timeout=15))
def binom(s):
    p = s.split(); return ' '.join(p[:2]) if len(p) >= 2 else None
def resolve(n):
    try:
        m = get('https://api.gbif.org/v1/species/match?' + urllib.parse.urlencode({'name': n, 'strict': 'true'}))
        key = m.get('acceptedUsageKey') or m.get('usageKey')
        if not key: return n, None
        cands = []
        if m.get('species'): cands.append(m['species'])
        for r in get(f'https://api.gbif.org/v1/species/{key}/synonyms?limit=200').get('results', []):
            b = binom(r.get('canonicalName', '') or '')
            if b: cands.append(b)
        for c in cands:
            if c in bc and c != n: return n, c
        return n, None
    except Exception as e:
        return n, None
import sys
res = {}
with ThreadPoolExecutor(32) as ex:
    for i, (k, v) in enumerate(ex.map(resolve, todo)):
        res[k] = v
        if i % 500 == 0:
            json.dump({a: b for a, b in res.items() if b}, open("/tmp/bc-data/syn.json", "w"))
            print(i, sum(1 for x in res.values() if x), flush=True)
hit = {k: v for k, v in res.items() if v}
print('resolved', len(hit)); print(list(hit.items())[:15])
json.dump(hit, open('/tmp/bc-data/syn.json', 'w'))
