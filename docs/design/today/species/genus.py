import json, time, urllib.request
UA = {"User-Agent": "MinisResearch/0.1 (species list for on-device ID; nazhenhuiyi@gmail.com)"}
d = json.load(open('/tmp/bc-data/cn_inat.json'))
ids = sorted({x['anc'][-2] for x in d if x['c'] >= 3 and x.get('anc') and len(x['anc']) >= 2})
print('genera', len(ids), flush=True)
out = {}
for i in range(0, len(ids), 30):
    chunk = ids[i:i + 30]
    url = 'https://api.inaturalist.org/v1/taxa/' + ','.join(map(str, chunk)) + '?locale=zh-CN&per_page=30'
    for a in range(5):
        try: r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20)); break
        except Exception as e: time.sleep(10)
    for t in r.get('results', []):
        out[t['id']] = {'n': t['name'], 'rank': t['rank'], 'zh': t.get('preferred_common_name')}
    if i % 600 == 0: print(i, len(out), flush=True)
    time.sleep(1.1)
json.dump(out, open('/tmp/bc-data/cn_genus.json', 'w'), ensure_ascii=False)
print('done', len(out), sum(1 for v in out.values() if v['zh']))
