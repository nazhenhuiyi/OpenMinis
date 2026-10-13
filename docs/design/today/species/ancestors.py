import json, time, urllib.request
UA = {"User-Agent": "MinisResearch/0.1 (species list for on-device ID; nazhenhuiyi@gmail.com)"}
d = [x for x in json.load(open('/tmp/bc-data/cn_all.json')) if x['c'] >= 3]
known = json.load(open('/tmp/bc-data/cn_genus.json'))
ids = sorted({a for x in d for a in (x.get('anc') or [])[:-2]} - {int(k) for k in known})
print('ids', len(ids), flush=True)
out = {}
for i in range(0, len(ids), 30):
    url = 'https://api.inaturalist.org/v1/taxa/' + ','.join(map(str, ids[i:i + 30])) + '?locale=zh-CN&per_page=30'
    for a in range(5):
        try: r = json.load(urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20)); break
        except Exception: time.sleep(8); r = {}
    for t in r.get('results', []): out[t['id']] = {'n': t['name'], 'rank': t['rank'], 'zh': t.get('preferred_common_name')}
    if i % 1500 == 0: print(i, len(out), flush=True)
    time.sleep(1.0)
json.dump(out, open('/tmp/bc-data/cn_ancestors.json', 'w'), ensure_ascii=False)
print('done', len(out))
