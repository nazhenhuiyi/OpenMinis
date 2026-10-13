"""Wild (cn_species.py) + cultivated/captive (cn_captive.py) sightings in China → cn_all.json, counts added."""
import json
wild = {x['n']: x for x in json.load(open('/tmp/bc-data/cn_inat.json'))}
cap = {x['n']: x for x in json.load(open('/tmp/bc-data/cn_captive.json'))}
out = []
for n in set(wild) | set(cap):
    w, c = wild.get(n), cap.get(n)
    x = dict(w or c)
    x['c'] = (w['c'] if w else 0) + (c['c'] if c else 0)
    x['wild'] = w['c'] if w else 0
    x['zh'] = (w and w.get('zh')) or (c and c.get('zh'))
    out.append(x)
out.sort(key=lambda x: -x['c'])
json.dump(out, open('/tmp/bc-data/cn_all.json', 'w'), ensure_ascii=False)
print(len(out), 'species,', sum(1 for x in out if x['c'] >= 3), 'with ≥3 sightings')
