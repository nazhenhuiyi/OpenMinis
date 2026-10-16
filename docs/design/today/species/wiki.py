"""species_wiki.<lang>.tsv for SpeciesInfo: the Wikipedia lead paragraph of every species and genus in species.tsv, so the species page needs no network.

Lines are `latin \t title \t summary`, one per species or genus that has an
article (species keys have a space, genus keys don't). A species without its
own line falls back to its genus' in the app, as before. lang: zh-cn, zh-tw, en.

How an article is found (what the app used to do live, but by scientific name first):
1. Wikidata: the item whose taxon name (P225) is the latin, and its zh / en sitelink.
   A name held by more than one item (Morus: mulberries and gannets) is only
   taken if the zh article names our Chinese name.
2. zh: the Chinese name as a title (redirects followed: 家猫 → 猫).
   en: the latin as a title (most have a redirect).
Then the lead of each title in batches of 20, with zh variant conversion.
Disambiguation pages are skipped. Responses are kept under /tmp/bc-data/wiki/,
so a rerun only asks for what is missing.

Wikipedia's own conversion leaves some leads half simplified, half
traditional, so zh is put in one script with OpenCC (`pip install opencc`);
the brackets left empty where templates were cut out ("（学名：）") go too.
"""
import json, os, re, sys, time, urllib.parse, urllib.request, hashlib
from concurrent.futures import ThreadPoolExecutor
import opencc

D = '/tmp/bc-data'
C = f'{D}/wiki'
UA = 'Minis/1.0 (species info build for an offline nature collection; nazhenhuiyi@gmail.com)'
os.makedirs(C, exist_ok=True)


def http(url, data=None, accept=None, tries=6):
    for k in range(tries):
        try:
            req = urllib.request.Request(url, data=data, headers={'User-Agent': UA, **({'Accept': accept} if accept else {})})
            with urllib.request.urlopen(req, timeout=60) as r:
                return json.load(r)
        except Exception as e:
            if k == tries - 1: raise
            time.sleep(2 ** k)


def cached(key, fn):
    f = f'{C}/{hashlib.sha1(key.encode()).hexdigest()}.json'
    if os.path.exists(f): return json.load(open(f))
    v = fn()
    json.dump(v, open(f, 'w'), ensure_ascii=False)
    return v


rows = [l.rstrip('\n').split('\t') for l in open(f'{D}/species/species.tsv') if l.strip()]
zh_of = {}  # latin (species or genus) → Chinese name
for r in rows:
    zh_of.setdefault(r[0], r[1] if len(r) > 1 else '')
    g = r[2] if len(r) > 2 and r[2] else r[0].split(' ')[0]
    if not zh_of.get(g): zh_of[g] = r[3] if len(r) > 3 else ''
keys = sorted(zh_of)
print(len(rows), 'species,', len(keys) - len(rows), 'genera', flush=True)

# ── 1. Wikidata sitelinks ──
def sparql(names):
    vals = ' '.join(json.dumps(n) for n in names)
    q = ('SELECT ?name ?item ?zh ?en WHERE { VALUES ?name {' + vals + '} ?item wdt:P225 ?name. '
         'OPTIONAL{?zh schema:about ?item; schema:isPartOf <https://zh.wikipedia.org/>} '
         'OPTIONAL{?en schema:about ?item; schema:isPartOf <https://en.wikipedia.org/>} }')
    j = http('https://query.wikidata.org/sparql', data=urllib.parse.urlencode({'query': q}).encode(), accept='application/sparql-results+json')
    return [{k: b[k]['value'] for k in b} for b in j['results']['bindings']]

def title(url):
    return urllib.parse.unquote(url.rsplit('/wiki/', 1)[1]).replace('_', ' ')

items = {}  # latin → {item: (zh, en)}
B = 250
batches = [keys[i:i + B] for i in range(0, len(keys), B)]
with ThreadPoolExecutor(3) as ex:
    for n, res in enumerate(ex.map(lambda b: cached('wd:' + '|'.join(b), lambda: sparql(b)), batches)):
        for b in res:
            items.setdefault(b['name'], {})[b['item']] = (b.get('zh') and title(b['zh']), b.get('en') and title(b['en']))
        if n % 20 == 0: print('wikidata', n, '/', len(batches), flush=True)

# ── 2. Leads, in batches of 20 titles ──
def leads(host, titles, variant):
    """title asked → (final title, first paragraph) for real articles."""
    out = {}
    titles = sorted(set(titles))
    chunks = [titles[i:i + 20] for i in range(0, len(titles), 20)]
    def one(chunk):
        p = {'action': 'query', 'prop': 'extracts|pageprops', 'ppprop': 'disambiguation', 'exintro': 1, 'explaintext': 1,
             'exlimit': 20, 'redirects': 1, 'format': 'json', 'formatversion': 2, 'titles': '|'.join(chunk)}
        if variant: p['variant'] = variant
        return cached(f'lead:{host}:{variant}:' + '|'.join(chunk), lambda: http(f'https://{host}/w/api.php?' + urllib.parse.urlencode(p)))
    with ThreadPoolExecutor(8) as ex:
        for n, (chunk, j) in enumerate(zip(chunks, ex.map(one, chunks))):
            q = j.get('query', {})
            hop = {}
            for m in q.get('normalized', []) + q.get('redirects', []): hop[m['from']] = m['to']
            pages = {p['title']: p for p in q.get('pages', [])}
            for t in chunk:
                f = t
                for _ in range(3): f = hop.get(f, f)
                p = pages.get(f)
                if not p or p.get('missing') or p.get('invalid') or 'disambiguation' in (p.get('pageprops') or {}): continue
                para = next((s.strip() for s in p.get('extract', '').split('\n') if s.strip()), '')
                if para: out[t] = (f, ' '.join(para.split()))
            if n % 200 == 0: print(host, variant, n, '/', len(chunks), flush=True)
    return out


# "（学名：）", "（英语：；）", "()": what is left of a bracket whose contents were templates.
EMPTY = re.compile(r'\s*[（(]\s*(?:[^（）()：:；;，,]{1,8}[：:]\s*[；;，,、]?\s*)*[）)]')
# "，英语：" before a bracket closes or the next item: a label whose value was a template.
EMPTY_LABEL = re.compile(r'[，,；;、]?\s*[^（）()：:；;，,、\s]{1,8}[：:]\s*(?=[）)，,；;、])')
SCRIPT = {'zh-cn': opencc.OpenCC('t2s'), 'zh-tw': opencc.OpenCC('s2tw')}


def clean(text, lang):
    if lang in SCRIPT: text = SCRIPT[lang].convert(text)
    return EMPTY.sub('', EMPTY_LABEL.sub('', text))


def build(lang):
    host = 'en.wikipedia.org' if lang == 'en' else 'zh.wikipedia.org'
    variant = None if lang == 'en' else lang
    want = {}  # latin → candidate titles, best first
    for k in keys:
        its = items.get(k, {})
        zh = zh_of.get(k) or ''
        if lang == 'en':
            sure = [v[1] for v in its.values() if v[1]]
            c = sure[:1] if len(its) == 1 else []
            c.append(k)
            if len(its) > 1: c += sure  # only the one matching the zh pick, below
        else:
            sure = [v[0] for v in its.values() if v[0]]
            c = sure[:1] if len(its) == 1 else []
            if zh: c.append(zh)
            if len(its) > 1: c += sure  # checked against the Chinese name below
        want[k] = c
    got = leads(host, [t for c in want.values() for t in c], variant)
    zh_pick = picks.get('zh') or {}
    lines = []
    for k in keys:
        its, zh = items.get(k, {}), zh_of.get(k) or ''
        hit = None
        for t in want[k]:
            h = got.get(t)
            if not h: continue
            if len(its) > 1 and lang != 'en' and t != zh and not (zh and (zh in h[0] or zh in h[1])): continue
            if len(its) > 1 and lang == 'en' and t != k: continue
            hit = h
            break
        # en for a name several items share: the item whose zh article we took.
        if lang == 'en' and len(its) > 1 and k in zh_pick:
            for zt, et in its.values():
                if zt == zh_pick[k] and et and got.get(et): hit = got[et]
        if hit:
            if lang != 'en': picks.setdefault('zh', {})[k] = hit[0]
            lines.append(f'{k}\t{hit[0]}\t{clean(hit[1], lang)}\n')
    with open(f'{D}/species/species_wiki.{lang}.tsv', 'w') as f: f.writelines(lines)
    sp = sum(1 for l in lines if ' ' in l.split('\t')[0])
    print(lang, len(lines), 'lines,', sp, 'species,', len(lines) - sp, 'genera,', round(sum(len(l.encode()) for l in lines) / 1e6, 1), 'MB', flush=True)


picks = {}
for lang in (sys.argv[1:] or ['zh-cn', 'zh-tw', 'en']):
    build(lang)
