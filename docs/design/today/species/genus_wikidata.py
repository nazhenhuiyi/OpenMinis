"""Chinese names for genera iNat has none for, from Wikidata (taxon name P225 + rank genus), simplified first."""
import json, sys, time, urllib.parse, urllib.request
rows = [l.rstrip('\n').split('\t') for l in open('/tmp/bc-data/species/species.tsv')]
have = {r[2] for r in rows if r[3]}
need = sorted({r[2] for r in rows} - have)
print('genera without zh', len(need), flush=True)
UA = {"User-Agent": "MinisResearch/0.1 (genus names for on-device species ID; nazhenhuiyi@gmail.com)", "Accept": "application/sparql-results+json"}
out = {}
for i in range(0, len(need), 150):
    vals = ' '.join('"%s"' % n.replace('"', '') for n in need[i:i + 150])
    q = """SELECT ?name ?hans ?zh WHERE { VALUES ?name { %s } ?item wdt:P225 ?name; wdt:P105 wd:Q34740.
      OPTIONAL { ?item rdfs:label ?hans FILTER(LANG(?hans) = "zh-hans") } OPTIONAL { ?item rdfs:label ?zh FILTER(LANG(?zh) = "zh") } }""" % vals
    for a in range(4):
        try:
            r = json.load(urllib.request.urlopen(urllib.request.Request("https://query.wikidata.org/sparql?query=" + urllib.parse.quote(q), headers=UA), timeout=60)); break
        except Exception as e:
            print('retry', e, flush=True); time.sleep(10); r = {'results': {'bindings': []}}
    for b in r['results']['bindings']:
        name = b['name']['value']; label = (b.get('hans') or b.get('zh') or {}).get('value')
        if label and name not in out: out[name] = label if label.endswith('属') else label + '属'
    print(i, len(out), flush=True); time.sleep(1)
json.dump(out, open('/tmp/bc-data/genus_wikidata.json', 'w'), ensure_ascii=False)
print('found', len(out), list(out.items())[:12])
